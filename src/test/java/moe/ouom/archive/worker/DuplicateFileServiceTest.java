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
}
