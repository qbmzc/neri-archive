package moe.ouom.archive.worker;

import jakarta.annotation.PreDestroy;
import moe.ouom.archive.store.ArchiveStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static moe.ouom.archive.store.ArchiveStore.*;

@Component
public class DuplicateFileService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DuplicateFileService.class);
    private static final Set<String> AUDIO_EXTENSIONS=Set.of("flac","mp3","m4a","aac","wav","ogg","opus","wma","ape");
    private final ArchiveStore store;
    private final MediaFiles files;
    private final boolean scanOnStartup;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("duplicate-file-scan").daemon(true).unstarted(r));
    private volatile boolean running;
    private volatile long startedAt;
    private volatile long completedAt;
    private volatile int errorCount;
    private volatile String error="";

    public DuplicateFileService(ArchiveStore store,MediaFiles files,
                                @Value("${archive.duplicate-scan-on-startup:true}") boolean scanOnStartup) {
        this.store=store; this.files=files; this.scanOnStartup=scanOnStartup;
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
        List<InventoryEntry> snapshot=new ArrayList<>();
        int failures=0;
        long now=System.currentTimeMillis();
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
                    String hash=old!=null&&number(old,"bytes")==bytes&&number(old,"modified_at")==modified
                            ?text(old,"sha256"):MediaFiles.hash(path,"SHA-256");
                    snapshot.add(new InventoryEntry(relative,bytes,modified,hash,now));
                } catch(Exception e) {
                    failures++;
                    log.warn("跳过无法检查的音频文件 {}：{}",files.root.relativize(path),DownloadWorker.safeError(e));
                }
            }
        }
        snapshot.sort(Comparator.comparing(InventoryEntry::path));
        store.replaceFileInventory(snapshot);
        errorCount=failures;
        log.info("重复文件扫描完成：{} 个音频文件，{} 组重复，{} 个文件读取失败",
                snapshot.size(),groups(snapshot).size(),failures);
    }

    private boolean isAudio(Path path) {
        if(path.startsWith(files.root.resolve(".work"))||Files.isSymbolicLink(path)) return false;
        String name=Objects.toString(path.getFileName(),"");
        int dot=name.lastIndexOf('.');
        return dot>=0&&AUDIO_EXTENSIONS.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));
    }

    public Map<String,Object> report() {
        List<InventoryEntry> entries=store.fileInventory().stream().map(row -> new InventoryEntry(
                text(row,"path"),number(row,"bytes"),number(row,"modified_at"),text(row,"sha256"),number(row,"scanned_at"))).toList();
        List<Map<String,Object>> groups=groups(entries);
        long duplicateFiles=groups.stream().mapToLong(g->((List<?>)g.get("files")).size()-1).sum();
        long reclaimable=groups.stream().mapToLong(g->number(g,"reclaimableBytes")).sum();
        long persistedScanAt=entries.stream().mapToLong(InventoryEntry::scannedAt).max().orElse(0);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("state",running?"RUNNING":error.isBlank()?"IDLE":"FAILED");
        result.put("startedAt",startedAt); result.put("completedAt",completedAt>0?completedAt:persistedScanAt);
        result.put("fileCount",entries.size()); result.put("duplicateGroups",groups.size());
        result.put("duplicateFiles",duplicateFiles); result.put("reclaimableBytes",reclaimable);
        result.put("errorCount",errorCount); result.put("error",error); result.put("groups",groups);
        return result;
    }

    private static List<Map<String,Object>> groups(List<InventoryEntry> entries) {
        Map<String,List<InventoryEntry>> byHash=new LinkedHashMap<>();
        for(var entry:entries) byHash.computeIfAbsent(entry.sha256(),ignored->new ArrayList<>()).add(entry);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var group:byHash.entrySet()) {
            if(group.getValue().size()<2) continue;
            long bytes=group.getValue().getFirst().bytes();
            result.add(Map.of("sha256",group.getKey(),"bytes",bytes,"reclaimableBytes",bytes*(group.getValue().size()-1),
                    "files",group.getValue().stream().map(InventoryEntry::path).sorted().toList()));
        }
        result.sort(Comparator.comparingLong((Map<String,Object> g)->number(g,"reclaimableBytes")).reversed());
        return result;
    }

    @PreDestroy void close() { executor.shutdownNow(); }
}
