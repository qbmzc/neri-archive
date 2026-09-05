package moe.ouom.archive.worker;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import moe.ouom.archive.store.ArchiveStore;
import moe.ouom.archive.netease.MusicGateway;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Files;
import static moe.ouom.archive.store.ArchiveStore.*;

@Component
public class ArchiveEngine {
    private final ArchiveStore store;
    private final MusicGateway gateway;
    private final DownloadWorker worker;
    private final MediaFiles files;
    private final int parallelism;
    private final boolean enabled;
    private final ExecutorService downloads;
    private final Set<Long> running=ConcurrentHashMap.newKeySet();
    private long lastRepair=0;
    public ArchiveEngine(ArchiveStore store,MusicGateway gateway,DownloadWorker worker,MediaFiles files,
                         @Value("${archive.parallelism}") int parallelism,@Value("${archive.scheduling}") boolean enabled) {
        this.store=store; this.gateway=gateway; this.worker=worker; this.files=files;
        this.parallelism=Math.clamp(parallelism,1,8); this.enabled=enabled;
        downloads=Executors.newFixedThreadPool(this.parallelism);
    }
    @PostConstruct void recover() { store.recover(); }
    @Scheduled(fixedDelay=1000) public synchronized void dispatch() {
        if(!enabled) return;
        while(running.size()<parallelism) {
            var task=store.claim(); if(task.isEmpty()) break;
            long id=number(task,"id"); running.add(id);
            downloads.submit(()->{
                try { worker.run(task); exportPlaylists(); }
                finally { running.remove(id); }
            });
        }
    }
    @Scheduled(fixedDelay=10000,initialDelay=3000) public void scan() {
        if(!enabled) return;
        long now=System.currentTimeMillis();
        for(var sub:store.subscriptions()) {
            if(number(sub,"enabled")==0||number(sub,"next_scan")>now) continue;
            try { store.applySnapshot(gateway.playlist(number(sub,"id"))); }
            catch(MusicGateway.AuthRequired e) { store.scanFailure(number(sub,"id"),e.getMessage()); }
            catch(Exception e) { store.scanFailure(number(sub,"id"),DownloadWorker.safeError(e)); }
        }
        exportPlaylists();
        if(now-lastRepair>24L*3600*1000) { repairMissing(); lastRepair=now; }
    }
    public synchronized void control(long id,String action) {
        if(action.equals("retry")&&running.contains(id)) throw new IllegalArgumentException("任务正在停止，请稍后再继续");
        store.control(id,action);
    }
    public void repairMissing() {
        for(var song:store.library()) {
            try { if(!Files.isRegularFile(files.safe(text(song,"path")))) store.enqueue(number(song,"id"),"FIDELITY"); }
            catch(Exception ignored) { /* An invalid path is never followed. */ }
        }
    }
    public void exportPlaylists() {
        for(var sub:store.subscriptions()) {
            try { files.playlist(number(sub,"id"),text(sub,"name"),store.playlistSongs(number(sub,"id"))); }
            catch(Exception ignored) { /* Retry export on the next scan; audio is independent of M3U8. */ }
        }
    }
    @PreDestroy void close() { downloads.shutdownNow(); }
}
