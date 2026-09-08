package moe.ouom.archive;

import moe.ouom.archive.store.ArchiveStore;
import moe.ouom.archive.netease.MusicGateway.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static moe.ouom.archive.store.ArchiveStore.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@AutoConfigureMockMvc
class StoreAndSecurityTest {
    static final Path ROOT;
    static { try { ROOT=Files.createTempDirectory("neri-store-test-"); } catch(Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("archive.data",()->ROOT.toString()); p.add("archive.music",()->ROOT.resolve("music").toString());
        p.add("spring.datasource.url",()->"jdbc:sqlite:"+ROOT.resolve("test.db"));
        p.add("archive.admin-password",()->"test-password-only-123"); p.add("archive.scheduling",()->false);
        p.add("archive.duplicate-scan-on-startup",()->false);
    }
    @Autowired ArchiveStore store;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @BeforeEach void reset() { db.update("DELETE FROM tasks"); db.update("DELETE FROM members"); db.update("DELETE FROM subscriptions"); db.update("DELETE FROM songs"); db.update("DELETE FROM audio_fingerprints"); db.update("DELETE FROM file_inventory"); }
    Track track(long id) { return new Track(id,"Song "+id,"Artist","Album","",180000,1); }
    Playlist playlist(long id,Track... tracks) { return new Playlist(id,"Playlist",List.of(tracks)); }
    @Test void firstSnapshotAndCrossPlaylistDeduplication() {
        store.subscribe(1,15,true,"FIDELITY",false); store.applySnapshot(playlist(1,track(11),track(12)));
        store.subscribe(2,15,true,"FIDELITY",false); store.applySnapshot(playlist(2,track(11)));
        assertEquals(2,store.tasks().size()); assertEquals(2,store.playlistSongs(1).size()); assertEquals(1,store.playlistSongs(2).size());
        store.applySnapshot(playlist(1,track(11))); assertEquals(2,store.tasks().size()); assertFalse(store.song(12).isEmpty());
    }
    @Test void newOnlyBaselineDoesNotDownloadOldSongs() {
        store.subscribe(1,15,false,"FIDELITY",false); store.applySnapshot(playlist(1,track(11)));
        assertTrue(store.tasks().isEmpty());
        store.applySnapshot(playlist(1,track(11),track(12))); assertEquals(1,store.tasks().size()); assertEquals(12,number(store.tasks().getFirst(),"song_id"));
    }
    @Test void failedSnapshotRollsBackSongsQueueAndMembers() {
        store.subscribe(1,15,true,"FIDELITY",false); store.applySnapshot(playlist(1,track(11)));
        Track invalid=new Track(13,null,"A","B","",1000,1);
        assertThrows(Exception.class,()->store.applySnapshot(playlist(1,track(12),invalid)));
        assertEquals(List.of(11L),store.playlistSongs(1).stream().map(r->number(r,"id")).toList());
        assertTrue(store.song(12).isEmpty()); assertEquals(1,store.tasks().size());
    }
    @Test void cancelledWorkCannotCommitAndRecoveryKeepsPausedTasksPaused() {
        store.subscribe(1,15,true,"FIDELITY",false); store.applySnapshot(playlist(1,track(11),track(12)));
        var running=store.claim(); var second=store.claim();
        store.control(number(second,"id"),"pause"); store.recover();
        assertEquals("QUEUED",text(store.task(number(running,"id")),"status"));
        assertEquals("PAUSED",text(store.task(number(second,"id")),"status"));
        store.claim(); store.control(number(running,"id"),"cancel"); store.outcome(number(running,"id"),"DONE","",0);
        assertEquals("CANCELLED",text(store.task(number(running,"id")),"status"));
        assertThrows(IllegalStateException.class,()->store.complete(number(running,"id"),number(running,"song_id"),"must-not-commit.flac","lossless",100,"hash",44100,16,1000,""));
        assertTrue(text(store.song(number(running,"song_id")),"path").isBlank());
    }
    @Test void deletingSubscriptionPreservesMusicAndTasks() {
        store.subscribe(1,15,true,"FIDELITY",false); store.applySnapshot(playlist(1,track(11))); store.delete(1);
        assertTrue(store.playlistSongs(1).isEmpty()); assertFalse(store.song(11).isEmpty()); assertEquals(1,store.tasks().size());
    }
    @Test void retryCannotCreateTwoActiveTasks() {
        store.subscribe(1,15,true,"FIDELITY",false); store.applySnapshot(playlist(1,track(11)));
        long old=number(store.tasks().getFirst(),"id"); store.control(old,"cancel"); store.enqueue(11,"FIDELITY");
        assertThrows(IllegalArgumentException.class,()->store.control(old,"retry"));
    }
    @Test void runtimeLogsRequireLoginAndSupportFiltering() throws Exception {
        org.slf4j.LoggerFactory.getLogger("runtime-test").warn("task #54 diagnostic MUSIC_U=private-value");
        mvc.perform(get("/api/logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/logs").with(user("admin")).param("level","WARN").param("query","task #54 diagnostic"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$[0].level").value("WARN"))
            .andExpect(jsonPath("$[0].message").value("task #54 diagnostic MUSIC_U=[已隐藏]"));
        mvc.perform(get("/api/logs").with(user("admin")).param("level","ERROR").param("query","task #54 diagnostic"))
            .andExpect(jsonPath("$.length()").value(0));
    }
    @Test void apiRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/healthz")).andExpect(status().isOk());
        mvc.perform(post("/api/subscriptions").with(user("admin")).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
        mvc.perform(post("/api/subscriptions").with(user("admin")).with(csrf()).contentType("application/json")
                .content("{\"source\":\"12345\",\"intervalMinutes\":15,\"initialDownload\":true,\"policy\":\"FIDELITY\",\"autoUpgrade\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(12345));
        mvc.perform(get("/api/csrf").with(user("admin"))).andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(get("/api/library/duplicates").with(user("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicateGroups").value(0));
        mvc.perform(post("/api/library/duplicates/scan").with(user("admin")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.started").value(true));
    }
    @Test void scannedFilesAreSearchableAndPaginated() throws Exception {
        long now=System.currentTimeMillis();
        db.update("INSERT INTO file_inventory(path,bytes,modified_at,sha256,scanned_at) VALUES(?,?,?,?,?)","Artist/Album/a.flac",10,now,"same",now);
        db.update("INSERT INTO file_inventory(path,bytes,modified_at,sha256,scanned_at) VALUES(?,?,?,?,?)","Artist/Album/b.flac",10,now,"same",now);
        db.update("INSERT INTO file_inventory(path,bytes,modified_at,sha256,scanned_at) VALUES(?,?,?,?,?)","Other/unique.mp3",7,now,"unique",now);
        db.update("INSERT INTO audio_fingerprints(path,duration_seconds,fingerprint_hash,group_id,match_type,scanned_at) VALUES(?,?,?,?,?,?)","Artist/Album/a.flac",180,1,"exact-1","EXACT",now);
        db.update("INSERT INTO audio_fingerprints(path,duration_seconds,fingerprint_hash,group_id,match_type,scanned_at) VALUES(?,?,?,?,?,?)","Artist/Album/b.flac",180,1,"exact-1","EXACT",now);
        db.update("INSERT INTO audio_fingerprints(path,duration_seconds,fingerprint_hash,group_id,match_type,scanned_at) VALUES(?,?,?,?,?,?)","Other/unique.mp3",200,2,"","",now);
        mvc.perform(get("/api/library/files").with(user("admin")).param("pageSize","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(1)).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].duplicate_count").value(2));
        mvc.perform(get("/api/library/files").with(user("admin")).param("query","unique"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].path").value("Other/unique.mp3"));
        mvc.perform(get("/api/library/files").with(user("admin")).param("filter","DUPLICATE"))
                .andExpect(jsonPath("$.total").value(2));
    }
    @Test void actualPasswordLoginWorks() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","test-password-only-123"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","incorrect-password"))
                .andExpect(redirectedUrl("/login?error"));
    }
}
