package moe.ouom.archive.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import moe.ouom.archive.store.ArchiveStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuplicateFileServiceTest {
    @TempDir Path root;

    @Test void scansExistingAudioAndFallsBackToExactContentWithoutFpcalc() throws Exception {
        var store=mock(ArchiveStore.class);
        when(store.fileInventory()).thenReturn(List.of());
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        Files.createDirectories(root.resolve("old/album"));
        Files.writeString(root.resolve("old/album/one.flac"),"same audio");
        Files.writeString(root.resolve("old/album/copy.mp3"),"same audio");
        Files.writeString(root.resolve("old/album/other.m4a"),"different");
        Files.writeString(root.resolve("old/album/cover.jpg"),"same audio");
        Files.writeString(root.resolve(".work/unfinished.flac"),"same audio");

        var service=new DuplicateFileService(store,files,false,"missing-fpcalc-for-test");
        service.scanNow();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArchiveStore.InventoryEntry>> captured=ArgumentCaptor.forClass(List.class);
        verify(store).replaceFileInventory(captured.capture());
        var entries=captured.getValue();
        assertEquals(3,entries.size());
        assertEquals(2,entries.stream().filter(e->e.matchType().equals("EXACT")).count());
        assertTrue(entries.stream().allMatch(e->e.audioHash()==-1));
        service.close();
    }

    @Test void scanResolvesArchivedSongIdAndSkipsTrash() throws Exception {
        var store=mock(ArchiveStore.class);
        when(store.fileInventory()).thenReturn(List.of());
        when(store.library()).thenReturn(List.<Map<String,Object>>of(Map.of("id",77,"path","old/album/one.flac")));
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        Files.createDirectories(root.resolve("old/album"));
        Files.writeString(root.resolve("old/album/one.flac"),"archived audio");
        Files.writeString(root.resolve("old/album/untracked.flac"),"untracked audio");
        Files.createDirectories(files.trash.resolve("11"));
        Files.writeString(files.trash.resolve("11/20260101-000000000-replaced.flac"),"superseded audio");

        var service=new DuplicateFileService(store,files,false,"missing-fpcalc-for-test");
        service.scanNow();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArchiveStore.InventoryEntry>> captured=ArgumentCaptor.forClass(List.class);
        verify(store).replaceFileInventory(captured.capture());
        var entries=captured.getValue();
        assertEquals(2,entries.size());
        assertEquals(77L,entryNamed(entries,"one.flac").songId());
        assertNull(entryNamed(entries,"untracked.flac").songId());
        service.close();
    }

    private static ArchiveStore.InventoryEntry entryNamed(List<ArchiveStore.InventoryEntry> entries,String name) {
        return entries.stream().filter(e->e.path().endsWith(name)).findFirst().orElseThrow();
    }

    @Test void groupsSimilarAudioFingerprintsButChecksDuration() {
        long now=2;
        var entries=List.of(
                entry("lossless.flac",10,"sha-a",0b10101010,180,raw(0xaaaaaaaa),now),
                entry("transcoded.mp3",7,"sha-b",0b10101011,181,raw(0xaaaaaaab),now),
                entry("different-song.mp3",8,"sha-c",0b10101011,240,raw(0xaaaaaaab),now));
        var grouped=DuplicateFileService.assignDuplicateGroups(entries);
        assertEquals("AUDIO",grouped.get(0).matchType());
        assertEquals(grouped.get(0).duplicateGroup(),grouped.get(1).duplicateGroup());
        assertTrue(grouped.get(2).duplicateGroup().isBlank());
    }

    @Test void rejectsSimilarSummaryHashesWhenFullFingerprintsDiffer() {
        var entries=List.of(
                entry("song-a.flac",10,"sha-a",1,180,raw(0x00000000),2),
                entry("song-b.flac",10,"sha-b",1,180,raw(0xffffffff),2));
        var grouped=DuplicateFileService.assignDuplicateGroups(entries);
        assertTrue(grouped.stream().allMatch(entry->entry.duplicateGroup().isBlank()));
    }

    @Test void doesNotMergeAudioMatchesTransitively() {
        var entries=List.of(
                entry("song-a.flac",10,"sha-a",0,180,raw(0),2),
                entry("song-b.flac",10,"sha-b",7,180,raw(7),2),
                entry("song-c.flac",10,"sha-c",63,180,raw(63),2));
        var grouped=DuplicateFileService.assignDuplicateGroups(entries);
        assertEquals(grouped.get(0).duplicateGroup(),grouped.get(1).duplicateGroup());
        assertTrue(grouped.get(2).duplicateGroup().isBlank());
    }

    @Test void reportCalculatesReclaimableBytesForAudioMatches() throws Exception {
        var store=mock(ArchiveStore.class);
        when(store.fileInventory()).thenReturn(List.of(
                row("a.flac",10,"sha-a",1,"audio-1","AUDIO"),
                row("b.mp3",7,"sha-b",1,"audio-1","AUDIO"),
                row("c.mp3",6,"sha-c",2,"","")));
        var service=new DuplicateFileService(store,new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper()),false,"missing");
        var report=service.report();
        assertEquals(1,report.get("duplicateGroups"));
        assertEquals(1L,report.get("duplicateFiles"));
        assertEquals(7L,report.get("reclaimableBytes"));
        assertEquals(2L,report.get("completedAt"));
        service.close();
    }

    private static ArchiveStore.InventoryEntry entry(String path,long bytes,String sha,long hash,double duration,String raw,long scannedAt) {
        return new ArchiveStore.InventoryEntry(path,bytes,1,sha,hash,duration,raw,"","",null,scannedAt);
    }
    private static String raw(int value) {
        return java.util.stream.IntStream.range(0,100).mapToObj(ignored->Integer.toUnsignedString(value)).collect(java.util.stream.Collectors.joining(","));
    }
    private static Map<String,Object> row(String path,long bytes,String sha,long hash,String group,String type) {
        return Map.of("path",path,"bytes",bytes,"modified_at",1,"sha256",sha,"audio_hash",hash,
                "audio_duration",180,"audio_fingerprint",raw(1),"duplicate_group",group,"match_type",type,"scanned_at",2);
    }

    @Test void cleanupKeepsHigherBitrateCopyAndTrashesTheRest() throws Exception {
        var store=mock(ArchiveStore.class);
        Files.createDirectories(root.resolve("album"));
        Files.writeString(root.resolve("album/keep.flac"),"k".repeat(400));
        Files.writeString(root.resolve("album/drop.mp3"),"d".repeat(100));
        when(store.fileInventory()).thenReturn(List.of(
                fileRow("album/keep.flac","audio-1",180,null),
                fileRow("album/drop.mp3","audio-1",180,null)));
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        var service=new DuplicateFileService(store,files,false,"missing");
        Map<String,Object> result=service.cleanup();
        assertEquals(1,result.get("moved"));
        assertTrue(Files.exists(root.resolve("album/keep.flac")));
        assertFalse(Files.exists(root.resolve("album/drop.mp3")));
        List<Path> trashed=trashEntries(files);
        assertEquals(1,trashed.size());
        assertEquals("d".repeat(100),Files.readString(trashed.getFirst()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> removed=ArgumentCaptor.forClass(Collection.class);
        verify(store).removeInventory(removed.capture());
        assertEquals(List.of("album/drop.mp3"),new ArrayList<>(removed.getValue()));
        verify(store,never()).repointSong(anyLong(),anyString(),anyLong(),anyString(),anyInt(),anyInt(),anyInt());
        service.close();
    }

    @Test void cleanupRepointsArchivedSongToBetterUntrackedCopy() throws Exception {
        var store=mock(ArchiveStore.class);
        Files.createDirectories(root.resolve("album"));
        Files.writeString(root.resolve("album/old.mp3"),"o".repeat(100));
        Files.writeString(root.resolve("album/new.flac"),"n".repeat(400));
        when(store.fileInventory()).thenReturn(List.of(
                fileRow("album/old.mp3","audio-1",180,11L),
                fileRow("album/new.flac","audio-1",180,null)));
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        var service=new DuplicateFileService(store,files,false,"missing");
        Map<String,Object> result=service.cleanup();
        assertEquals(1,result.get("moved"));
        assertEquals(1,result.get("repointed"));
        verify(store).repointSong(eq(11L),eq("album/new.flac"),eq(400L),anyString(),anyInt(),anyInt(),anyInt());
        assertTrue(Files.exists(root.resolve("album/new.flac")));
        assertFalse(Files.exists(root.resolve("album/old.mp3")));
        service.close();
    }

    @Test void cleanupPrefersArchivedCopyWhenContentIsIdentical() throws Exception {
        var store=mock(ArchiveStore.class);
        Files.createDirectories(root.resolve("album"));
        Files.writeString(root.resolve("album/archived.flac"),"identical audio");
        Files.writeString(root.resolve("album/orphan.flac"),"identical audio");
        when(store.fileInventory()).thenReturn(List.of(
                fileRow("album/orphan.flac","exact-1",180,null),
                fileRow("album/archived.flac","exact-1",180,11L)));
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        var service=new DuplicateFileService(store,files,false,"missing");
        Map<String,Object> result=service.cleanup();
        assertEquals(1,result.get("moved"));
        assertTrue(Files.exists(root.resolve("album/archived.flac")));
        assertFalse(Files.exists(root.resolve("album/orphan.flac")));
        verify(store,never()).repointSong(anyLong(),anyString(),anyLong(),anyString(),anyInt(),anyInt(),anyInt());
        service.close();
    }

    @Test void cleanupSkipsGroupsWithMultipleArchivedSongs() throws Exception {
        var store=mock(ArchiveStore.class);
        Files.createDirectories(root.resolve("album"));
        Files.writeString(root.resolve("album/a.flac"),"a".repeat(400));
        Files.writeString(root.resolve("album/b.flac"),"b".repeat(200));
        when(store.fileInventory()).thenReturn(List.of(
                fileRow("album/a.flac","audio-1",180,11L),
                fileRow("album/b.flac","audio-1",180,12L)));
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        var service=new DuplicateFileService(store,files,false,"missing");
        Map<String,Object> result=service.cleanup();
        assertEquals(0,result.get("moved"));
        assertEquals(1,result.get("skippedGroups"));
        assertTrue(Files.exists(root.resolve("album/a.flac")));
        assertTrue(Files.exists(root.resolve("album/b.flac")));
        service.close();
    }

    @Test void cleanupSkipsFilesChangedAfterScan() throws Exception {
        var store=mock(ArchiveStore.class);
        Files.createDirectories(root.resolve("album"));
        Files.writeString(root.resolve("album/keep.flac"),"k".repeat(400));
        Files.writeString(root.resolve("album/drop.mp3"),"d".repeat(100));
        var rows=List.of(fileRow("album/keep.flac","audio-1",180,null),fileRow("album/drop.mp3","audio-1",180,null));
        Files.writeString(root.resolve("album/drop.mp3"),"changed".repeat(50));
        when(store.fileInventory()).thenReturn(rows);
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        var service=new DuplicateFileService(store,files,false,"missing");
        Map<String,Object> result=service.cleanup();
        assertEquals(0,result.get("moved"));
        assertTrue(Files.exists(root.resolve("album/drop.mp3")));
        service.close();
    }

    private Map<String,Object> fileRow(String path,String group,double duration,Long songId) throws Exception {
        Path file=root.resolve(path);
        Map<String,Object> result=new HashMap<>();
        result.put("path",path);
        result.put("bytes",Files.size(file));
        result.put("modified_at",Files.getLastModifiedTime(file).toMillis());
        result.put("sha256",MediaFiles.hash(file,"SHA-256"));
        result.put("audio_hash",1);
        result.put("audio_duration",duration);
        result.put("audio_fingerprint",raw(1));
        result.put("duplicate_group",group);
        result.put("match_type","AUDIO");
        result.put("scanned_at",2);
        if(songId!=null) result.put("song_id",songId);
        return result;
    }
    private static List<Path> trashEntries(MediaFiles files) throws Exception {
        if(!Files.isDirectory(files.trash)) return List.of();
        try(var paths=Files.walk(files.trash)) { return paths.filter(Files::isRegularFile).toList(); }
    }
}
