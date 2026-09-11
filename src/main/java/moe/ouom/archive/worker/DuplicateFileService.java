package moe.ouom.archive.worker;

import jakarta.annotation.PreDestroy;
import moe.ouom.archive.store.ArchiveStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static moe.ouom.archive.store.ArchiveStore.*;

@Component
public class DuplicateFileService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DuplicateFileService.class);
    private static final Set<String> AUDIO_EXTENSIONS=Set.of("flac","mp3","m4a","aac","wav","ogg","opus","wma","ape","aif","aiff","alac","dsf","dff","mka");
    private static final Pattern DURATION=Pattern.compile("(?m)^DURATION=([0-9]+(?:\\.[0-9]+)?)$");
    private static final Pattern FINGERPRINT=Pattern.compile("(?m)^FINGERPRINT=([0-9,]+)$");
    private static final int MAX_ALIGNMENT_OFFSET=12;
    private static final double MAX_AUDIO_BIT_ERROR_RATE=0.10;
    private final ArchiveStore store;
    private final MediaFiles files;
    private final boolean scanOnStartup;
    private final String fpcalc;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("duplicate-file-scan").daemon(true).unstarted(r));
    private volatile boolean running;
    private volatile long startedAt;
    private volatile long completedAt;
    private volatile int errorCount;
    private volatile String error="";
    private volatile String fingerprintWarning="";

    public DuplicateFileService(ArchiveStore store,MediaFiles files,
                                @Value("${archive.duplicate-scan-on-startup:true}") boolean scanOnStartup,
                                @Value("${archive.fpcalc:fpcalc}") String fpcalc) {
        this.store=store; this.files=files; this.scanOnStartup=scanOnStartup; this.fpcalc=fpcalc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialScan() { if(scanOnStartup) requestScan(); }

    public synchronized boolean requestScan() {
        if(running) return false;
        running=true; startedAt=System.currentTimeMillis(); error="";
        executor.submit(this::scanSafely);
        return true;
    }

    private void scanSafely() {
        try { scanNow(); }
        catch(Exception e) {
            error=DownloadWorker.safeError(e);
            log.error("重复文件扫描失败：{}",error);
        } finally { completedAt=System.currentTimeMillis(); running=false; }
    }

    void scanNow() throws Exception {
        Map<String,Map<String,Object>> previous=new HashMap<>();
        for(var row:store.fileInventory()) previous.put(text(row,"path"),row);
        // 扫描已经走遍音乐目录，顺手把「文件属于哪首歌」解析出来落成整数列，
        // 查询时就能用 song_id 等值连接，不必再做路径字符串匹配。
        Map<String,Long> songByPath=new HashMap<>();
        for(var song:store.library()) songByPath.put(text(song,"path").replace('\\','/'),number(song,"id"));
        List<InventoryEntry> snapshot=new ArrayList<>();
        int failures=0;
        long now=System.currentTimeMillis();
        boolean audioFingerprinting=fpcalcAvailable();
        fingerprintWarning=audioFingerprinting?"":"未找到 fpcalc，新文件或已变化文件只能检查 SHA-256";
        if(!audioFingerprinting) log.warn(fingerprintWarning);
        try(var paths=Files.walk(files.root)) {
            var iterator=paths.iterator();
            while(iterator.hasNext()) {
                Path path=iterator.next();
                if(!isAudio(path)) continue;
                try {
                    BasicFileAttributes attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!attrs.isRegularFile()) continue;
                    String relative=files.root.relativize(path).toString().replace('\\','/');
                    long modified=attrs.lastModifiedTime().toMillis(),bytes=attrs.size();
                    var old=previous.get(relative);
                    boolean unchanged=old!=null&&number(old,"bytes")==bytes&&number(old,"modified_at")==modified;
                    String hash=unchanged?text(old,"sha256"):MediaFiles.hash(path,"SHA-256");
                    long audioHash=-1; double duration=0; String rawFingerprint="";
                    if(unchanged&&number(old,"audio_hash")>=0&&!text(old,"audio_fingerprint").isBlank()) {
                            audioHash=number(old,"audio_hash");
                            Object value=old.get("audio_duration"); duration=value instanceof Number n?n.doubleValue():0;
                            rawFingerprint=text(old,"audio_fingerprint");
                    } else if(audioFingerprinting) {
                            try {
                                var fingerprint=fingerprint(path); audioHash=fingerprint.hash(); duration=fingerprint.duration(); rawFingerprint=fingerprint.raw();
                            } catch(Exception e) {
                                failures++;
                                log.warn("文件 SHA-256 已记录，但音频指纹生成失败 {}：{}",relative,DownloadWorker.safeError(e));
                            }
                    }
                    snapshot.add(new InventoryEntry(relative,bytes,modified,hash,audioHash,duration,rawFingerprint,"","",songByPath.get(relative),now));
                } catch(Exception e) {
                    failures++;
                    log.warn("跳过无法检查的音频文件 {}：{}",files.root.relativize(path),DownloadWorker.safeError(e));
                }
            }
        }
        snapshot.sort(Comparator.comparing(InventoryEntry::path));
        snapshot=assignDuplicateGroups(snapshot);
        store.replaceFileInventory(snapshot);
        errorCount=failures;
        log.info("重复文件扫描完成：{} 个音频文件，{} 组重复，{} 个文件处理异常，比较模式={}",
                snapshot.size(),groups(snapshot).size(),failures,audioFingerprinting?"Chromaprint 音频指纹 + SHA-256":"SHA-256");
    }

    record Fingerprint(double duration,long hash,String raw) {}

    Fingerprint fingerprint(Path path) throws Exception {
        Path output=Files.createTempFile(files.safe(".work"),"fpcalc-",".log");
        Process process=null;
        try {
            process=new ProcessBuilder(fpcalc,"-raw","-length","120",path.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if(!process.waitFor(120,TimeUnit.SECONDS)) throw new IOException("音频指纹计算超时");
            if(process.exitValue()!=0||Files.size(output)>2L*1024*1024) throw new IOException("无法生成音频指纹");
            String result=Files.readString(output,StandardCharsets.UTF_8);
            var durationMatch=DURATION.matcher(result); var fingerprintMatch=FINGERPRINT.matcher(result);
            if(!durationMatch.find()||!fingerprintMatch.find()) throw new IOException("音频指纹输出无效");
            String[] values=fingerprintMatch.group(1).split(",");
            if(values.length<4) throw new IOException("音频过短，无法生成可靠指纹");
            int[] bits=new int[32];
            for(String value:values) {
                int sample=(int)Long.parseUnsignedLong(value);
                for(int bit=0;bit<32;bit++) bits[bit]+=(sample>>>bit)&1;
            }
            int simHash=0,threshold=values.length/2;
            for(int bit=0;bit<32;bit++) if(bits[bit]>threshold) simHash|=1<<bit;
            return new Fingerprint(Double.parseDouble(durationMatch.group(1)),Integer.toUnsignedLong(simHash),fingerprintMatch.group(1));
        } finally {
            if(process!=null&&process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    private boolean fpcalcAvailable() {
        try {
            var process=new ProcessBuilder(fpcalc,"-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5,TimeUnit.SECONDS)&&process.exitValue()==0;
        } catch(Exception ignored) { return false; }
    }

    static List<InventoryEntry> assignDuplicateGroups(List<InventoryEntry> entries) {
        int[][] fingerprints=entries.stream().map(entry->parseFingerprint(entry.audioFingerprint())).toArray(int[][]::new);
        List<InventoryEntry> result=new ArrayList<>(entries); int groupNumber=0;
        boolean[] grouped=new boolean[entries.size()];
        for(int i=0;i<entries.size();i++) {
            if(grouped[i]) continue;
            List<Integer> indexes=new ArrayList<>(); indexes.add(i);
            for(int j=i+1;j<entries.size();j++) {
                if(grouped[j]) continue;
                var left=entries.get(i); var right=entries.get(j);
                boolean exact=left.sha256().equals(right.sha256());
                boolean sameAudio=!exact&&left.audioHash()>=0&&right.audioHash()>=0
                        &&Math.abs(left.audioDuration()-right.audioDuration())<=3
                        &&Integer.bitCount((int)left.audioHash()^(int)right.audioHash())<=3
                        &&fingerprintsMatch(fingerprints[i],fingerprints[j]);
                if(exact||sameAudio) indexes.add(j);
            }
            if(indexes.size()<2) continue;
            boolean exact=indexes.stream().map(index->entries.get(index).sha256()).distinct().count()==1;
            String type=exact?"EXACT":"AUDIO",group=(exact?"exact-":"audio-")+(++groupNumber);
            for(int index:indexes) {
                var e=entries.get(index);
                grouped[index]=true;
                result.set(index,new InventoryEntry(e.path(),e.bytes(),e.modifiedAt(),e.sha256(),e.audioHash(),e.audioDuration(),e.audioFingerprint(),group,type,e.songId(),e.scannedAt()));
            }
        }
        return result;
    }

    private static int[] parseFingerprint(String value) {
        if(value==null||value.isBlank()) return new int[0];
        try {
            String[] values=value.split(","); int[] result=new int[values.length];
            for(int i=0;i<values.length;i++) result[i]=(int)Long.parseUnsignedLong(values[i]);
            return result;
        } catch(NumberFormatException ignored) { return new int[0]; }
    }

    static boolean fingerprintsMatch(int[] left,int[] right) {
        int shortest=Math.min(left.length,right.length);
        if(shortest<80||shortest*10<Math.max(left.length,right.length)*9) return false;
        double best=1;
        for(int offset=-MAX_ALIGNMENT_OFFSET;offset<=MAX_ALIGNMENT_OFFSET;offset++) {
            int leftStart=Math.max(0,-offset),rightStart=Math.max(0,offset);
            int overlap=Math.min(left.length-leftStart,right.length-rightStart);
            if(overlap<80) continue;
            long differentBits=0;
            for(int i=0;i<overlap;i++) differentBits+=Integer.bitCount(left[leftStart+i]^right[rightStart+i]);
            best=Math.min(best,differentBits/(overlap*32.0));
        }
        return best<=MAX_AUDIO_BIT_ERROR_RATE;
    }

    private boolean isAudio(Path path) {
        if(Files.isSymbolicLink(path)||files.isIgnored(path)) return false;
        String name=Objects.toString(path.getFileName(),"");
        int dot=name.lastIndexOf('.');
        return dot>=0&&AUDIO_EXTENSIONS.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));
    }

    public Map<String,Object> report() {
        List<InventoryEntry> entries=store.fileInventory().stream().map(row -> new InventoryEntry(
                text(row,"path"),number(row,"bytes"),number(row,"modified_at"),text(row,"sha256"),number(row,"audio_hash"),
                row.get("audio_duration") instanceof Number n?n.doubleValue():0,text(row,"audio_fingerprint"),text(row,"duplicate_group"),text(row,"match_type"),
                row.get("song_id") instanceof Number id?id.longValue():null,number(row,"scanned_at"))).toList();
        List<Map<String,Object>> groups=groups(entries);
        long duplicateFiles=groups.stream().mapToLong(g->((List<?>)g.get("files")).size()-1).sum();
        long reclaimable=groups.stream().mapToLong(g->number(g,"reclaimableBytes")).sum();
        long persistedScanAt=entries.stream().mapToLong(InventoryEntry::scannedAt).max().orElse(0);
        boolean hasAudioFingerprints=entries.stream().anyMatch(e->e.audioHash()>=0);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("state",running?"RUNNING":error.isBlank()?"IDLE":"FAILED");
        result.put("startedAt",startedAt); result.put("completedAt",completedAt>0?completedAt:persistedScanAt);
        result.put("fileCount",entries.size()); result.put("duplicateGroups",groups.size());
        result.put("duplicateFiles",duplicateFiles); result.put("reclaimableBytes",reclaimable);
        result.put("comparisonMode",hasAudioFingerprints?"AUDIO_FINGERPRINT":"EXACT_ONLY");
        result.put("fingerprintWarning",fingerprintWarning.isBlank()&&!entries.isEmpty()&&!hasAudioFingerprints?"现有扫描结果不含音频指纹，请确认已安装 fpcalc 后重新扫描":fingerprintWarning);
        result.put("errorCount",errorCount); result.put("error",error); result.put("groups",groups);
        return result;
    }

    private static List<Map<String,Object>> groups(List<InventoryEntry> entries) {
        Map<String,List<InventoryEntry>> grouped=new LinkedHashMap<>();
        for(var entry:entries) if(!entry.duplicateGroup().isBlank()) grouped.computeIfAbsent(entry.duplicateGroup(),ignored->new ArrayList<>()).add(entry);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var value:grouped.values()) {
            long total=value.stream().mapToLong(InventoryEntry::bytes).sum();
            long largest=value.stream().mapToLong(InventoryEntry::bytes).max().orElse(0);
            result.add(Map.of("matchType",value.getFirst().matchType(),"bytes",largest,"reclaimableBytes",total-largest,
                    "files",value.stream().map(InventoryEntry::path).sorted().toList()));
        }
        result.sort(Comparator.comparingLong((Map<String,Object> g)->number(g,"reclaimableBytes")).reversed());
        return result;
    }

    @PreDestroy void close() { executor.shutdownNow(); }
}
