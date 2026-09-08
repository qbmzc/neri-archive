package moe.ouom.archive.store;

import moe.ouom.archive.netease.MusicGateway.*;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;

@Repository
public class ArchiveStore {
    public record InventoryEntry(String path,long bytes,long modifiedAt,String sha256,long audioHash,double audioDuration,
                                 String duplicateGroup,String matchType,long scannedAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public ArchiveStore(JdbcTemplate db,PlatformTransactionManager tm) { this.db=db; tx=new TransactionTemplate(tm); }
    public static long number(Map<String,Object> row,String key) { Object v=row.get(key); return v instanceof Number n?n.longValue():0; }
    public static String text(Map<String,Object> row,String key) { return Objects.toString(row.get(key),""); }
    public List<Map<String,Object>> subscriptions() { return db.queryForList("SELECT * FROM subscriptions ORDER BY id DESC"); }
    public Map<String,Object> subscription(long id) { return one("SELECT * FROM subscriptions WHERE id=?",id); }
    public Map<String,Object> song(long id) { return one("SELECT * FROM songs WHERE id=?",id); }
    public Map<String,Object> task(long id) { return one("SELECT * FROM tasks WHERE id=?",id); }
    private Map<String,Object> one(String sql,Object... args) { var rows=db.queryForList(sql,args); return rows.isEmpty()?Map.of():rows.getFirst(); }
    public List<Map<String,Object>> tasks() { return db.queryForList("SELECT t.*,s.name,s.artist FROM tasks t JOIN songs s ON t.song_id=s.id ORDER BY t.id DESC LIMIT 500"); }
    public List<Map<String,Object>> library() { return db.queryForList("SELECT * FROM songs WHERE path IS NOT NULL ORDER BY downloaded_at DESC"); }
    public List<Map<String,Object>> fileInventory() { return db.queryForList("SELECT f.*,COALESCE(a.duration_seconds,0) AS audio_duration,COALESCE(a.fingerprint_hash,-1) AS audio_hash,COALESCE(a.group_id,'') AS duplicate_group,COALESCE(a.match_type,'') AS match_type FROM file_inventory f LEFT JOIN audio_fingerprints a ON a.path=f.path ORDER BY f.path"); }
    public Map<String,Object> fileInventoryPage(int requestedPage,int requestedPageSize,String requestedQuery,String requestedFilter) {
        int page=Math.max(1,requestedPage),pageSize=Math.clamp(requestedPageSize,10,100);
        String query=Objects.toString(requestedQuery,"").trim().toLowerCase(Locale.ROOT);
        String filter=Objects.toString(requestedFilter,"ALL").toUpperCase(Locale.ROOT);
        if(!Set.of("ALL","ARCHIVED","UNTRACKED","DUPLICATE").contains(filter)) throw new IllegalArgumentException("文件筛选条件无效");
        StringBuilder where=new StringBuilder(" WHERE 1=1");
        List<Object> args=new ArrayList<>();
        if(!query.isBlank()) {
            where.append(" AND (lower(f.path) LIKE ? ESCAPE '\\' OR lower(COALESCE(s.name,'')) LIKE ? ESCAPE '\\' OR lower(COALESCE(s.artist,'')) LIKE ? ESCAPE '\\' OR lower(COALESCE(s.album,'')) LIKE ? ESCAPE '\\')");
            String pattern="%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            for(int i=0;i<4;i++) args.add(pattern);
        }
        switch(filter) {
            case "ARCHIVED" -> where.append(" AND s.id IS NOT NULL");
            case "UNTRACKED" -> where.append(" AND s.id IS NULL");
            case "DUPLICATE" -> where.append(" AND a.group_id<>''");
        }
        String from=" FROM file_inventory f LEFT JOIN audio_fingerprints a ON a.path=f.path LEFT JOIN songs s ON replace(s.path,'\\','/')=f.path";
        long total=db.queryForObject("SELECT count(*)"+from+where,Long.class,args.toArray());
        int totalPages=Math.max(1,(int)Math.ceil(total/(double)pageSize));
        page=Math.min(page,totalPages);
        List<Object> pageArgs=new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((page-1)*pageSize);
        String select="SELECT f.*,s.id AS song_id,s.name,s.artist,s.album,a.duration_seconds AS audio_duration,a.fingerprint_hash AS audio_hash,a.match_type,"+
                "CASE WHEN a.group_id='' OR a.group_id IS NULL THEN 1 ELSE (SELECT count(*) FROM audio_fingerprints d WHERE d.group_id=a.group_id) END AS duplicate_count";
        var items=db.queryForList(select+from+where+" ORDER BY f.path LIMIT ? OFFSET ?",pageArgs.toArray());
        return Map.of("items",items,"total",total,"page",page,"pageSize",pageSize,"totalPages",totalPages,"filter",filter,"query",query);
    }
    public synchronized void replaceFileInventory(List<InventoryEntry> entries) {
        tx.executeWithoutResult(status -> {
            db.update("DELETE FROM audio_fingerprints");
            db.update("DELETE FROM file_inventory");
            for(var entry:entries) db.update("INSERT INTO file_inventory(path,bytes,modified_at,sha256,scanned_at) VALUES(?,?,?,?,?)",
                    entry.path(),entry.bytes(),entry.modifiedAt(),entry.sha256(),entry.scannedAt());
            for(var entry:entries) db.update("INSERT INTO audio_fingerprints(path,duration_seconds,fingerprint_hash,group_id,match_type,scanned_at) VALUES(?,?,?,?,?,?)",
                    entry.path(),entry.audioDuration(),entry.audioHash(),entry.duplicateGroup(),entry.matchType(),entry.scannedAt());
        });
    }
    public List<Map<String,Object>> playlistSongs(long id) { return db.queryForList("SELECT s.* FROM members m JOIN songs s ON m.song_id=s.id WHERE m.playlist_id=? ORDER BY m.position",id); }
    public synchronized void subscribe(long id,int minutes,boolean initial,String policy,boolean upgrade) {
        if(id<=0||minutes<5||minutes>10080) throw new IllegalArgumentException("歌单 ID 无效或检查周期不在 5–10080 分钟内");
        if(!policy.equals("FIDELITY")&&!policy.equals("SURROUND")) throw new IllegalArgumentException("音质策略无效");
        db.update("INSERT INTO subscriptions(id,name,interval_minutes,initial_download,policy,auto_upgrade) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET interval_minutes=excluded.interval_minutes,policy=excluded.policy,auto_upgrade=excluded.auto_upgrade,next_scan=0",id,"歌单 "+id,minutes,initial?1:0,policy,upgrade?1:0);
    }
    public synchronized void enabled(long id,boolean enabled) { db.update("UPDATE subscriptions SET enabled=?,next_scan=0 WHERE id=?",enabled?1:0,id); }
    public synchronized void delete(long id) { db.update("DELETE FROM subscriptions WHERE id=?",id); }
    public synchronized void due(long id) { db.update("UPDATE subscriptions SET next_scan=0 WHERE id=?",id); }
    public synchronized void scanFailure(long id,String error) { db.update("UPDATE subscriptions SET error=?,next_scan=? WHERE id=?",error,System.currentTimeMillis()+300_000,id); }
    public synchronized void applySnapshot(Playlist playlist) {
        tx.executeWithoutResult(status -> {
            var sub=subscription(playlist.id());
            if(sub.isEmpty()||number(sub,"enabled")==0) return;
            Set<Long> old=new HashSet<>();
            db.queryForList("SELECT song_id FROM members WHERE playlist_id=?",playlist.id()).forEach(r->old.add(number(r,"song_id")));
            boolean initial=number(sub,"initialized")==0;
            long now=System.currentTimeMillis();
            boolean upgrade=number(sub,"auto_upgrade")==1&&now-number(sub,"last_upgrade")>7L*24*3600*1000;
            for(var song:playlist.tracks()) {
                db.update("INSERT INTO songs(id,name,artist,album,cover,duration,track_no) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,artist=excluded.artist,album=excluded.album,cover=excluded.cover,duration=excluded.duration,track_no=excluded.track_no",song.id(),song.name(),song.artist(),song.album(),song.cover(),song.duration(),song.trackNo());
                boolean shouldDownload=initial?number(sub,"initial_download")==1:!old.contains(song.id());
                var existing=song(song.id());
                if((shouldDownload&&text(existing,"path").isBlank()) || (upgrade&&!text(existing,"path").isBlank())) enqueue(song.id(),text(sub,"policy"));
            }
            db.update("DELETE FROM members WHERE playlist_id=?",playlist.id());
            int pos=0;
            for(var song:playlist.tracks()) db.update("INSERT INTO members(playlist_id,song_id,position) VALUES(?,?,?)",playlist.id(),song.id(),pos++);
            db.update("UPDATE subscriptions SET name=?,initialized=1,track_count=?,last_scan=?,next_scan=?,last_upgrade=?,error='' WHERE id=?",playlist.name(),playlist.tracks().size(),now,now+number(sub,"interval_minutes")*60_000,upgrade?now:number(sub,"last_upgrade"),playlist.id());
        });
    }
    public synchronized void enqueue(long songId,String policy) {
        if(song(songId).isEmpty()) throw new IllegalArgumentException("歌曲不存在");
        long now=System.currentTimeMillis();
        db.update("INSERT OR IGNORE INTO tasks(song_id,policy,created_at,updated_at) VALUES(?,?,?,?)",songId,policy,now,now);
    }
    public synchronized Map<String,Object> claim() {
        return tx.execute(status -> {
            var row=one("SELECT * FROM tasks WHERE status='QUEUED' AND next_attempt<=? ORDER BY id LIMIT 1",System.currentTimeMillis());
            if(row.isEmpty()) return row;
            int changed=db.update("UPDATE tasks SET status='RUNNING',attempts=attempts+1,error='',updated_at=? WHERE id=? AND status='QUEUED'",System.currentTimeMillis(),number(row,"id"));
            return changed==1?task(number(row,"id")):Map.of();
        });
    }
    public synchronized void recover() { db.update("UPDATE tasks SET status='QUEUED',error='服务重启，重新解析资源并恢复任务',next_attempt=0 WHERE status='RUNNING'"); }
    public synchronized void progress(long id,long done,long total,String requested,String actual) {
        db.update("UPDATE tasks SET bytes_done=?,total_bytes=?,requested_level=?,actual_level=?,updated_at=? WHERE id=? AND status='RUNNING'",done,total,requested,actual,System.currentTimeMillis(),id);
    }
    public synchronized void outcome(long id,String status,String error,long retryAt) {
        db.update("UPDATE tasks SET status=?,error=?,next_attempt=?,updated_at=? WHERE id=? AND status='RUNNING'",status,error,retryAt,System.currentTimeMillis(),id);
    }
    public synchronized void control(long id,String action) {
        var t=task(id); if(t.isEmpty()) throw new IllegalArgumentException("任务不存在");
        String current=text(t,"status");
        switch(action) {
            case "pause" -> { if(Set.of("RUNNING","QUEUED").contains(current)) db.update("UPDATE tasks SET status='PAUSED' WHERE id=?",id); }
            case "cancel" -> { if(Set.of("RUNNING","QUEUED","PAUSED","AUTH_REQUIRED").contains(current)) db.update("UPDATE tasks SET status='CANCELLED' WHERE id=?",id); }
            case "retry" -> {
                if(Set.of("FAILED","CANCELLED","PAUSED","AUTH_REQUIRED").contains(current)) {
                    long active=db.queryForObject("SELECT count(*) FROM tasks WHERE song_id=? AND status IN ('QUEUED','RUNNING','PAUSED','AUTH_REQUIRED') AND id<>?",Long.class,number(t,"song_id"),id);
                    if(active>0) throw new IllegalArgumentException("此歌曲已有待处理任务");
                    db.update("UPDATE tasks SET status='QUEUED',attempts=0,next_attempt=0,error='' WHERE id=?",id);
                }
            }
            default -> throw new IllegalArgumentException("未知任务操作");
        }
    }
    public synchronized void resumeAuth() { db.update("UPDATE tasks SET status='QUEUED',attempts=0,next_attempt=0 WHERE status='AUTH_REQUIRED'"); }
    public synchronized void complete(long taskId,long songId,String path,String level,long bytes,String hash,int rate,int bits,int bitrate,String warning) {
        tx.executeWithoutResult(s -> {
            if(!text(task(taskId),"status").equals("RUNNING")) throw new IllegalStateException("任务已停止，不能提交文件");
            db.update("UPDATE songs SET path=?,level=?,bytes=?,sha256=?,sample_rate=?,bits=?,bitrate=?,downloaded_at=?,metadata_warning=? WHERE id=?",path,level,bytes,hash,rate,bits,bitrate,System.currentTimeMillis(),warning,songId);
            outcome(taskId,"DONE",warning,0);
        });
    }
    public synchronized void metadataWarning(long songId,String warning) { db.update("UPDATE songs SET metadata_warning=CASE WHEN metadata_warning='' THEN ? ELSE metadata_warning||'；'||? END WHERE id=?",warning,warning,songId); }
    public Map<String,Object> stats() {
        return Map.of("subscriptions",db.queryForObject("SELECT count(*) FROM subscriptions",Long.class),
                "downloaded",db.queryForObject("SELECT count(*) FROM songs WHERE path IS NOT NULL",Long.class),
                "active",db.queryForObject("SELECT count(*) FROM tasks WHERE status IN ('QUEUED','RUNNING')",Long.class),
                "failed",db.queryForObject("SELECT count(*) FROM tasks WHERE status IN ('FAILED','AUTH_REQUIRED')",Long.class));
    }
}
