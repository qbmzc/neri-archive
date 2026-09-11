package moe.ouom.archive.worker;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.*;
import java.security.MessageDigest;
import static moe.ouom.archive.store.ArchiveStore.*;

@Component
public class MediaFiles {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MediaFiles.class);
    public record Probe(String extension,double duration,int rate,int bits,int bitrate) {}
    public final Path root;
    /** 被替换文件的回收站。默认 {@code <music>/.trash}，可用 ARCHIVE_TRASH 指向音乐目录之外。 */
    public final Path trash;
    private final Path work;
    private final int retentionDays;
    private final String ffprobe,ffmpeg;
    private final ObjectMapper mapper;
    public MediaFiles(@Value("${archive.music}") String music,@Value("${archive.trash:}") String trash,
                      @Value("${archive.superseded-retention-days:7}") int retentionDays,
                      @Value("${archive.ffprobe}") String ffprobe,@Value("${archive.ffmpeg}") String ffmpeg,ObjectMapper mapper) throws IOException {
        root=Path.of(music).toAbsolutePath().normalize();
        work=root.resolve(".work");
        this.trash=resolveTrash(trash);
        this.retentionDays=Math.max(0,retentionDays);
        Files.createDirectories(safe(".work")); Files.createDirectories(safe("playlists"));
        this.ffprobe=ffprobe; this.ffmpeg=ffmpeg; this.mapper=mapper;
        log.info("音乐目录 {}；回收站 {}（保留 {} 天，0 表示不保留）",root,this.trash,this.retentionDays);
        warnWhenTrashIsOnAnotherFilesystem();
    }
    /** 跨文件系统时原子移动不可用，会退化为复制后删除：能跑，但更慢且占用目标盘空间。 */
    private void warnWhenTrashIsOnAnotherFilesystem() {
        try {
            var music=Files.getFileStore(root);
            var target=Files.getFileStore(nearestExisting(this.trash));
            if(!music.name().equals(target.name())||!music.type().equals(target.type()))
                log.warn("回收站与音乐目录不在同一文件系统，被替换的文件将复制而非原子移动：{}",this.trash);
        } catch(Exception e) { log.debug("无法比较回收站与音乐目录的文件系统：{}",e.toString()); }
    }
    private static Path nearestExisting(Path path) throws IOException {
        Path candidate=path;
        while(candidate!=null&&!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)) candidate=candidate.getParent();
        if(candidate==null) throw new IOException("路径不存在："+path);
        return candidate;
    }
    private Path resolveTrash(String configured) throws IOException {
        if(configured==null||configured.isBlank()) return root.resolve(".trash");
        Path candidate=Path.of(configured).toAbsolutePath().normalize();
        if(candidate.equals(root)) throw new IOException("回收站目录不能与音乐目录相同："+candidate);
        // 音乐目录落在回收站之内会让扫描排除规则连带排除整个音乐库。
        if(root.startsWith(candidate)) throw new IOException("音乐目录不能位于回收站目录之内："+candidate);
        return candidate;
    }
    /** 扫描需要跳过的路径：清理工作目录、回收站（无论它是否位于音乐目录内），以及任何点号开头的顶层目录。 */
    public boolean isIgnored(Path path) {
        Path normalized=path.toAbsolutePath().normalize();
        if(normalized.startsWith(work)||normalized.startsWith(trash)) return true;
        if(!normalized.startsWith(root)||normalized.equals(root)) return false;
        Path relative=root.relativize(normalized);
        return relative.getNameCount()>=1&&relative.getName(0).toString().startsWith(".");
    }
    /**
     * 把被替换的文件移入回收站。
     *
     * <p>任何失败都不得删除源文件——回收站的职责是保命，不能变成新的数据丢失路径。
     * 移动不可行时旧文件留在原地，退回「孤儿文件」行为。
     *
     * @return 是否已移入回收站（或按 retention=0 删除）
     */
    public boolean moveToTrash(Path source,long songId) {
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)) return false;
        if(retentionDays==0) {
            try { Files.deleteIfExists(source); return true; }
            catch(IOException e) { log.warn("删除被替换的文件失败，保留原文件：{}",e.toString()); return false; }
        }
        Path directory=trash.resolve(Long.toString(songId));
        Path target=uniqueTarget(directory,source.getFileName().toString());
        try {
            Files.createDirectories(directory);
            try { Files.move(source,target,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e) {
                // 跨文件系统：退回普通移动（复制后删除），需要目标盘空间。
                log.warn("回收站与音乐目录不在同一文件系统，改为复制后删除：{}",target.getFileName());
                Files.move(source,target);
            }
            return true;
        } catch(IOException|UnsupportedOperationException e) {
            log.warn("无法将被替换的文件移入回收站，保留原文件：{}",e.toString());
            return false;
        }
    }
    private Path uniqueTarget(Path directory,String name) {
        String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").format(java.time.LocalDateTime.now());
        String safe=filename(name);
        Path candidate=directory.resolve(stamp+"-"+safe);
        for(int i=1;Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)&&i<100;i++) candidate=directory.resolve(stamp+"-"+i+"-"+safe);
        return candidate;
    }
    /** 按配置的保留天数清理回收站。返回删除数量。 */
    public int cleanupTrash() {
        return cleanupTrash(System.currentTimeMillis()-(long)retentionDays*86_400_000L);
    }
    /** 删除回收站中早于 {@code cutoffMillis} 的文件，返回删除数量。只记日志，不抛出。 */
    public int cleanupTrash(long cutoffMillis) {
        if(!Files.isDirectory(trash,LinkOption.NOFOLLOW_LINKS)) return 0;
        int removed=0;
        try(var paths=Files.walk(trash)) {
            for(Path path:paths.toList()) {
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) continue;
                if(!path.normalize().startsWith(trash)) continue;
                try {
                    if(Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()>=cutoffMillis) continue;
                    Files.deleteIfExists(path); removed++;
                } catch(IOException e) { log.warn("回收站文件删除失败 {}：{}",trash.relativize(path),e.toString()); }
            }
        } catch(IOException e) { log.warn("回收站清理失败：{}",e.toString()); return removed; }
        removeEmptyTrashDirectories();
        return removed;
    }
    private void removeEmptyTrashDirectories() {
        try(var paths=Files.walk(trash)) {
            for(Path directory:paths.sorted(Comparator.reverseOrder()).toList()) {
                if(directory.equals(trash)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) continue;
                try(var entries=Files.list(directory)) { if(entries.findAny().isEmpty()) Files.deleteIfExists(directory); }
                catch(IOException ignored) { /* 非空或已被并发删除 */ }
            }
        } catch(IOException ignored) { /* 回收站不存在或不可读 */ }
    }
    public Path safe(String relative) throws IOException {
        Path result=root.resolve(relative).normalize();
        if(!result.startsWith(root)||result.equals(root)) throw new IOException("非法文件路径");
        // Prevent a media subdirectory symlink from escaping the configured root.
        Path parent=result;
        while(parent!=null&&!parent.equals(root)) {
            if(Files.isSymbolicLink(parent)) throw new IOException("音乐目录中不允许符号链接路径");
            parent=parent.getParent();
        }
        return result;
    }
    public static String filename(String value) {
        String s=value.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]","_").replaceAll("[. ]+$","").trim();
        if(s.isBlank()) s="未知";
        if(s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) s="_"+s;
        return s.length()>70?s.substring(0,70):s;
    }
    public String relative(Map<String,Object> song,String ext,String hash) {
        return filename(text(song,"artist"))+"/"+filename(text(song,"album"))+"/"+filename(text(song,"name"))+" ["+number(song,"id")+"]-"+hash.substring(0,12)+"."+ext;
    }
    /** Match the exact destination, previous archive, and same-song filenames in this album. */
    public List<Path> versions(Map<String,Object> song,String ext,String destination) throws IOException {
        var result=new LinkedHashSet<Path>();
        Path target=safe(destination), directory=target.getParent();
        String old=text(song,"path");
        if(!old.isBlank()) addRegular(result,safe(old));
        addRegular(result,target);
        String title=filename(text(song,"name"));
        String stem=title+" ["+number(song,"id")+"]";
        if(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) {
            try(var entries=Files.newDirectoryStream(directory)) {
                for(Path entry:entries) {
                    String name=entry.getFileName().toString();
                    if(name.equals(title+"."+ext)||name.equals(stem+"."+ext)
                            ||name.matches(java.util.regex.Pattern.quote(stem)+"-[0-9a-fA-F]{12}\\."+java.util.regex.Pattern.quote(ext)))
                        addRegular(result,safe(root.relativize(entry).toString()));
                }
            }
        }
        return List.copyOf(result);
    }
    private static void addRegular(Set<Path> paths,Path path) throws IOException {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new FileSystemException(path.toString(),null,"音频目标不是普通文件");
            paths.add(path);
        }
    }
    private String run(List<String> args,int seconds) throws Exception {
        Path output=Files.createTempFile(root.resolve(".work"),"probe-",".log");
        Process process=null;
        try {
            process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if(!process.waitFor(seconds,TimeUnit.SECONDS)) throw new IOException("音频工具执行超时");
            if(process.exitValue()!=0) throw new IOException("音频工具执行失败，请确认文件有效且 ffmpeg/ffprobe 已安装");
            try(var in=Files.newInputStream(output)) { return new String(in.readNBytes(1024*1024),StandardCharsets.UTF_8); }
        } finally { if(process!=null&&process.isAlive()) process.destroyForcibly(); Files.deleteIfExists(output); }
    }
    public Probe probe(Path file,long expectedDurationMs) throws Exception {
        JsonNode result=mapper.readTree(run(List.of(ffprobe,"-v","error","-show_entries","format=format_name,duration,bit_rate:stream=codec_type,codec_name,sample_rate,bits_per_sample,bits_per_raw_sample","-of","json",file.toString()),30));
        JsonNode audio=null;
        for(JsonNode s:result.path("streams")) if(s.path("codec_type").asText().equals("audio")) { audio=s; break; }
        if(audio==null) throw new IOException("文件没有有效音轨");
        String ext=switch(audio.path("codec_name").asText()) { case "flac"->"flac"; case "mp3"->"mp3"; case "alac"->"m4a"; case "aac"->result.path("format").path("format_name").asText().contains("mp4")?"m4a":"aac"; default->throw new IOException("暂不支持返回的音频编码"); };
        double duration=result.path("format").path("duration").asDouble();
        validateDuration(duration,expectedDurationMs);
        return new Probe(ext,duration,audio.path("sample_rate").asInt(),Math.max(audio.path("bits_per_raw_sample").asInt(),audio.path("bits_per_sample").asInt()),result.path("format").path("bit_rate").asInt());
    }
    public static void validateDuration(double seconds,long expectedMs) throws IOException {
        if(!Double.isFinite(seconds)||seconds<=0) throw new IOException("无法验证音频时长");
        if(expectedMs>0&&Math.abs(seconds-expectedMs/1000.0)>Math.max(3,expectedMs/1000.0*0.02)) throw new IOException("音频时长不匹配，可能为片段或下载不完整");
    }
    public Path tag(Path source,Probe probe,Map<String,Object> song) throws Exception {
        Path output=source.resolveSibling(source.getFileName()+".tagged."+probe.extension());
        var args=new ArrayList<>(List.of(ffmpeg,"-nostdin","-v","error","-y","-i",source.toString(),"-map","0:a:0","-c:a","copy",
                "-metadata","title="+text(song,"name"),"-metadata","artist="+text(song,"artist"),"-metadata","album="+text(song,"album"),
                "-metadata","track="+number(song,"track_no"),output.toString()));
        try { run(args,60); return output; }
        catch(Exception e) { Files.deleteIfExists(output); throw e; }
    }
    public static String hash(Path path,String algorithm) throws Exception {
        var digest=MessageDigest.getInstance(algorithm);
        try(var input=Files.newInputStream(path)) { byte[] buf=new byte[128*1024]; int n; while((n=input.read(buf))!=-1) digest.update(buf,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void move(Path source,Path dest) throws IOException {
        if(!Files.isDirectory(dest.getParent(),LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(dest.getParent());
        Files.move(source,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public synchronized void playlist(long id,String name,List<Map<String,Object>> songs) throws IOException {
        StringBuilder content=new StringBuilder("#EXTM3U\n");
        for(var song:songs) {
            String path=text(song,"path");
            if(path.isBlank()||!Files.isRegularFile(safe(path))) continue;
            content.append("#EXTINF:").append(number(song,"duration")/1000).append(',').append(text(song,"artist").replaceAll("[\\r\\n]"," ")).append(" - ").append(text(song,"name").replaceAll("[\\r\\n]"," ")).append('\n').append("../").append(path.replace('\\','/')).append('\n');
        }
        Path dest=safe("playlists/"+id+".m3u8");
        if(Files.isRegularFile(dest)&&Files.readString(dest).contentEquals(content)) return;
        Path temp=dest.resolveSibling(dest.getFileName()+".tmp"); Files.writeString(temp,content); move(temp,dest);
    }
}
