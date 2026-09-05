package moe.ouom.archive.worker;

import moe.ouom.archive.store.ArchiveStore;
import moe.ouom.archive.netease.MusicGateway;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static moe.ouom.archive.store.ArchiveStore.*;

@Component
public class DownloadWorker {
    private final ArchiveStore store;
    private final MusicGateway api;
    private final MediaFiles files;
    private final ObjectMapper mapper;
    private final SafeHttp http;
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadWorker(ArchiveStore store,MusicGateway api,MediaFiles files,ObjectMapper mapper) { this(store,api,files,mapper,new SafeHttp()); }
    DownloadWorker(ArchiveStore store,MusicGateway api,MediaFiles files,ObjectMapper mapper,SafeHttp http) { this.store=store; this.api=api; this.files=files; this.mapper=mapper; this.http=http; }
    private void check(long taskId) throws InterruptedException {
        if(Thread.currentThread().isInterrupted()||!text(store.task(taskId),"status").equals("RUNNING")) throw new InterruptedException("任务已暂停或取消");
    }
    public void run(Map<String,Object> task) {
        long id=number(task,"id"),songId=number(task,"song_id");
        Path part=files.root.resolve(".work/"+id+".part"), signature=files.root.resolve(".work/"+id+".json");
        Path tagged=null;
        boolean transferred=false;
        try {
            var song=store.song(songId); String policy=text(task,"policy");
            check(id);
            var source=new QualitySelector().resolve(api,songId,policy);
            check(id);
            String existing=text(song,"path");
            if(!existing.isBlank()&&Files.isRegularFile(files.safe(existing))&&!QualitySelector.better(source.actual(),text(song,"level"),policy)) {
                store.outcome(id,"DONE","本地音质已达到本次可用最高档位",0); return;
            }
            transfer(id,source,part,signature);
            transferred=true;
            check(id);
            if(!source.md5().isBlank()&&!MediaFiles.hash(part,"MD5").equalsIgnoreCase(source.md5())) throw new IOException("下载内容 MD5 不匹配");
            var probe=files.probe(part,number(song,"duration"));
            check(id);
            String warning="";
            try { tagged=files.tag(part,probe,song); }
            catch(Exception e) { warning="标签写入失败，原始音频已保留"; }
            Path finished=tagged==null?part:tagged;
            String hash=MediaFiles.hash(finished,"SHA-256");
            String relative=files.relative(song,probe.extension(),hash);
            synchronized(store) {
                check(id);
                MediaFiles.move(finished,files.safe(relative));
                store.complete(id,songId,relative,source.actual(),Files.size(files.safe(relative)),hash,probe.rate(),probe.bits(),probe.bitrate(),warning);
            }
            // A distinct content-addressed path preserves the previous file until the DB references the new one.
            if(!existing.isBlank()&&!existing.equals(relative)) {
                try { Files.deleteIfExists(files.safe(existing)); } catch(IOException ignored) {}
            }
            Files.deleteIfExists(part); Files.deleteIfExists(signature);
            sidecars(song,relative);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch(MusicGateway.AuthRequired e) {
            store.outcome(id,"AUTH_REQUIRED",e.getMessage(),0);
        } catch(MusicGateway.Unavailable e) {
            store.outcome(id,"FAILED",e.getMessage(),0);
        } catch(Exception e) {
            int attempts=(int)number(task,"attempts");
            store.outcome(id,attempts>=5?"FAILED":"QUEUED",safeError(e),System.currentTimeMillis()+Math.min(3600,30L*(1L<<Math.min(attempts,6)))*1000);
            // Validation failures must not leave a corrupt full-size partial to resume.
            if(transferred) try { Files.deleteIfExists(part); Files.deleteIfExists(signature); } catch(IOException ignored) {}
        } finally {
            try {
                if(tagged!=null) Files.deleteIfExists(tagged);
                String state=text(store.task(id),"status");
                if(state.equals("CANCELLED")||state.equals("DONE")) { Files.deleteIfExists(part); Files.deleteIfExists(signature); }
            } catch(IOException ignored) {}
        }
    }
    static String safeError(Exception e) {
        if(e instanceof IOException) return "传输或文件校验失败："+(e.getMessage()==null?"I/O 错误":e.getMessage().replaceAll("https?://\\S+","[资源地址]")).substring(0,Math.min(240,(e.getMessage()==null?"I/O 错误":e.getMessage().replaceAll("https?://\\S+","[资源地址]")).length()));
        return "处理失败（"+e.getClass().getSimpleName()+"），请检查服务环境后重试";
    }
    void transfer(long id,QualitySelector.Resource source,Path part,Path signature) throws Exception {
        String fingerprint=source.md5()+":"+source.size()+":"+source.actual();
        long offset=0;
        if(!source.md5().isBlank()&&Files.exists(part)&&Files.exists(signature)&&Files.readString(signature).equals(fingerprint)) offset=Files.size(part);
        if(offset>source.size()&&source.size()>0) offset=0;
        if(offset==0) Files.deleteIfExists(part);
        Files.writeString(signature,fingerprint);
        if(source.size()>0&&offset==source.size()) return;
        var connection=http.open(source.url(),offset);
        try {
            int code=connection.getResponseCode();
            if(code!=200&&code!=206) throw new IOException("媒体 HTTP "+code);
            if(offset>0&&code==200) offset=0;
            if(code==206) validateRange(connection.getHeaderField("Content-Range"),offset,source.size());
            long length=connection.getContentLengthLong();
            long total=source.size()>0?source.size():(length>0?offset+length:0);
            if(source.size()>0&&length>=0&&offset+length!=source.size()) throw new IOException("媒体长度与资源信息不一致");
            long free=Files.getFileStore(files.root).getUsableSpace();
            if(free<Math.max(total-offset,0)+128L*1024*1024) throw new IOException("音乐目录磁盘空间不足");
            long done=offset,lastUpdate=0;
            try(var in=connection.getInputStream(); var out=Files.newOutputStream(part,StandardOpenOption.CREATE,offset>0?StandardOpenOption.APPEND:StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer=new byte[128*1024]; int n;
                while((n=in.read(buffer))!=-1) {
                    check(id); done+=n;
                    if((total>0&&done>total)||done>4L*1024*1024*1024) throw new IOException("媒体超出预期大小");
                    out.write(buffer,0,n);
                    if(System.currentTimeMillis()-lastUpdate>700) { store.progress(id,done,total,source.requested(),source.actual()); lastUpdate=System.currentTimeMillis(); }
                }
            }
            if(done==0||(total>0&&done!=total)) throw new IOException("音频文件下载不完整");
            store.progress(id,done,total,source.requested(),source.actual());
        } finally { connection.disconnect(); }
    }
    static void validateRange(String range,long offset,long size) throws IOException {
        if(range==null) throw new IOException("续传响应没有 Content-Range");
        var m=java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(range);
        if(!m.matches()||Long.parseLong(m.group(1))!=offset||Long.parseLong(m.group(2))<offset||
                Long.parseLong(m.group(2))>=Long.parseLong(m.group(3))||(size>0&&Long.parseLong(m.group(3))!=size)) throw new IOException("续传范围不一致");
    }
    private void sidecars(Map<String,Object> song,String relative) {
        try {
            Path audio=files.safe(relative);
            String name=audio.getFileName().toString(); String stem=name.substring(0,name.lastIndexOf('.'));
            var lyric=api.lyrics(number(song,"id"));
            for(var entry:Map.of("lrc",".lrc","tlyric",".translation.lrc","romalrc",".romanized.lrc").entrySet()) {
                String text=lyric.path(entry.getKey()).path("lyric").asText("");
                if(!text.isBlank()) Files.writeString(audio.resolveSibling(stem+entry.getValue()),text);
            }
            if(!text(song,"cover").isBlank()) http.smallFile(text(song,"cover"),audio.resolveSibling(stem+".cover.jpg"),10*1024*1024);
        } catch(Exception ignored) { store.metadataWarning(number(song,"id"),"歌词或封面获取失败，音频可正常使用"); }
    }
}
