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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DownloadWorker.class);
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
        String stage="解析资源";
        log.info("下载任务 #{} 歌曲 {} 开始，尝试 {} 次",id,songId,number(task,"attempts"));
        try {
            var song=store.song(songId); String policy=text(task,"policy");
            check(id);
            var source=new QualitySelector().resolve(api,songId,policy);
            check(id);
            String existing=text(song,"path");
            // 下载前决策：服务这次返回的档位不高于本地时，连传输都不必发生。
            // 两端档位都已知才跳过；任一端未知就放行，交由下载后的实测裁决，
            // 保持 README 的承诺——未知档位不会自动替换已知档位文件。
            // 本地文件已缺失（repairMissing 的补下载场景）时同样放行：此时 songs.level
            // 描述的是一个已经不存在的文件，拿它比较会让补下载被静默跳过。
            String localLevel=text(song,"level");
            if(number(task,"forced")==0&&localFilePresent(existing)
                    &&QualitySelector.known(localLevel,policy)&&QualitySelector.known(source.actual(),policy)
                    &&!QualitySelector.better(source.actual(),localLevel,policy)) {
                String reason="服务返回档位 "+source.actual()+" 不高于本地 "+localLevel+"，未下载";
                log.info("下载任务 #{} 跳过：{}",id,reason);
                store.outcome(id,"SKIPPED",reason,0);
                return;
            }
            stage="传输音频";
            transfer(id,source,part,signature);
            transferred=true;
            check(id);
            stage="校验音频";
            if(!source.md5().isBlank()&&!MediaFiles.hash(part,"MD5").equalsIgnoreCase(source.md5())) throw new IOException("下载内容 MD5 不匹配");
            var probe=files.probe(part,number(song,"duration"));
            check(id);
            String warning="";
            stage="写入标签";
            try { tagged=files.tag(part,probe,song); }
            catch(Exception e) { warning="标签写入失败，原始音频已保留"; log.warn("任务 #{} {}：{}",id,warning,safeError(e)); }
            Path finished=tagged==null?part:tagged;
            String hash=MediaFiles.hash(finished,"SHA-256");
            String relative=files.relative(song,probe.extension(),hash);
            synchronized(store) {
                check(id);
                stage="比较已有文件";
                var versions=files.versions(song,probe.extension(),relative);
                Path selected=selectRetained(finished,probe,versions,song,existing);
                String actual=source.actual();
                if(!selected.equals(finished)) {
                    // Read metadata from the file we keep, never from the discarded download.
                    probe=files.probe(selected,number(song,"duration"));
                    hash=MediaFiles.hash(selected,"SHA-256");
                    relative=files.root.relativize(selected).toString();
                    actual=relative.equals(existing)?text(song,"level"):"unknown";
                    warning=relative.equals(existing)?text(song,"metadata_warning"):"";
                    log.info("下载任务 #{} 保留已有文件（实测音质不劣于新下载）：{}",id,relative);
                } else {
                    stage="归档文件";
                    MediaFiles.move(finished,files.safe(relative));
                }
                check(id);
                stage="更新数据库";
                store.complete(id,songId,relative,actual,Files.size(files.safe(relative)),hash,probe.rate(),probe.bits(),probe.bitrate(),warning);
                // Only remove superseded versions after the database references the retained file.
                // 被替换的文件进回收站而不是直接删除，移动失败时保留原地，绝不删除。
                Path retained=files.safe(relative);
                for(Path version:versions) {
                    if(!version.equals(retained)) files.moveToTrash(version,songId);
                }
            }
            Files.deleteIfExists(part); Files.deleteIfExists(signature);
            sidecars(song,relative);
            log.info("下载任务 #{} 归档完成：{}",id,relative);
        } catch(InterruptedException e) {
            log.info("下载任务 #{} 已停止",id);
            Thread.currentThread().interrupt();
        } catch(MusicGateway.AuthRequired e) {
            log.warn("下载任务 #{} 需要重新登录",id);
            store.outcome(id,"AUTH_REQUIRED",e.getMessage(),0);
        } catch(MusicGateway.Unavailable e) {
            log.warn("下载任务 #{} 资源不可用：{}",id,safeError(e));
            store.outcome(id,"FAILED",e.getMessage(),0);
        } catch(Exception e) {
            log.error("下载任务 #{} 歌曲 {} 阶段={} 异常={}：{}",id,songId,stage,e.getClass().getSimpleName(),safeError(e));
            int attempts=(int)number(task,"attempts");
            store.outcome(id,attempts>=5?"FAILED":"QUEUED",stage+"："+safeError(e),System.currentTimeMillis()+Math.min(3600,30L*(1L<<Math.min(attempts,6)))*1000);
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
    /** 归档记录指向的文件是否真的还在盘上。路径非法时按缺失处理。 */
    boolean localFilePresent(String path) {
        if(path.isBlank()) return false;
        try { return Files.isRegularFile(files.safe(path),LinkOption.NOFOLLOW_LINKS); }
        catch(IOException e) { return false; }
    }
    /**
     * 决定保留新下载的文件还是已有文件。
     *
     * <p>判据是实测音质（无损/有损 → 采样率 → 位深 → 码率），不是字节数：字节数对
     * 「网易云把有损转码后标记为无损返回」这一场景会稳定选错，把真 320 换成假无损。
     * 音质等价时保留已有文件，避免重复下载产生抖动。
     */
    Path selectRetained(Path incoming,MediaFiles.Probe probe,List<Path> versions,Map<String,Object> song,String existing) throws Exception {
        QualityComparator.Profile best=QualityComparator.profile(probe);
        Path existingPath=existing.isBlank()?null:files.safe(existing);
        Path selected=incoming;
        for(Path version:versions) {
            QualityComparator.Profile candidate=qualityOf(version,song,existingPath);
            if(candidate==null) continue;
            int comparison=QualityComparator.compare(candidate,best);
            if(comparison>0||(comparison==0&&selected.equals(incoming))) { selected=version; best=candidate; }
        }
        return selected;
    }
    /** 已有文件优先使用 songs 行里已存的探测规格，避免重复调用 ffprobe。 */
    private QualityComparator.Profile qualityOf(Path version,Map<String,Object> song,Path existingPath) {
        try {
            if(version.equals(existingPath)) {
                QualityComparator.Profile stored=QualityComparator.stored(text(song,"path"),
                        (int)number(song,"sample_rate"),(int)number(song,"bits"),(int)number(song,"bitrate"));
                if(stored!=null) return stored;
            }
            return QualityComparator.profile(files.probe(version,0));
        } catch(Exception e) {
            log.debug("无法读取已有文件的音质，跳过比较 {}：{}",version,safeError(e));
            return null;
        }
    }
    static String safeError(Exception e) {
        String detail=moe.ouom.archive.logging.RuntimeLogAppender.redact(Objects.toString(e.getMessage(),"无详细信息"));
        detail=detail.substring(0,Math.min(240,detail.length()));
        String reason;
        if(e instanceof AccessDeniedException) reason="目录或文件无写入权限，请检查容器 UID/GID 与挂载目录权限";
        else if(e instanceof FileAlreadyExistsException || e instanceof NotDirectoryException) reason="目录路径被同名文件占用，请检查路径冲突";
        else if(e instanceof NoSuchFileException) reason="文件或目录不存在，请检查挂载路径";
        else if(e instanceof FileSystemException) reason="文件系统操作失败（权限、只读挂载或磁盘空间）";
        else if(e instanceof IOException) reason="传输或文件校验失败";
        else reason="处理失败";
        return reason+"（"+e.getClass().getSimpleName()+"）："+detail;
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
