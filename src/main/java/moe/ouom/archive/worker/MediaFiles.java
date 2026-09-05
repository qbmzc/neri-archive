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
    public record Probe(String extension,double duration,int rate,int bits,int bitrate) {}
    public final Path root;
    private final String ffprobe,ffmpeg;
    private final ObjectMapper mapper;
    public MediaFiles(@Value("${archive.music}") String music,@Value("${archive.ffprobe}") String ffprobe,@Value("${archive.ffmpeg}") String ffmpeg,ObjectMapper mapper) throws IOException {
        root=Path.of(music).toAbsolutePath().normalize(); Files.createDirectories(safe(".work")); Files.createDirectories(safe("playlists"));
        this.ffprobe=ffprobe; this.ffmpeg=ffmpeg; this.mapper=mapper;
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
        Files.createDirectories(dest.getParent()); Files.move(source,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
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
