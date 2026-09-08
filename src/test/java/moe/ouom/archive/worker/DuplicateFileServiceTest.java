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

    @Test void scansExistingAudioAndGroupsOnlyIdenticalContent() throws Exception {
        var store=mock(ArchiveStore.class);
        when(store.fileInventory()).thenReturn(List.of());
        var files=new MediaFiles(root.toString(),"ffprobe","ffmpeg",new ObjectMapper());
        Files.createDirectories(root.resolve("old/album"));
        Files.writeString(root.resolve("old/album/one.flac"),"same audio");
        Files.writeString(root.resolve("old/album/copy.mp3"),"same audio");
        Files.writeString(root.resolve("old/album/other.m4a"),"different");
        Files.writeString(root.resolve("old/album/cover.jpg"),"same audio");
        Files.writeString(root.resolve(".work/unfinished.flac"),"same audio");

        var service=new DuplicateFileService(store,files,false);
        service.scanNow();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArchiveStore.InventoryEntry>> captured=ArgumentCaptor.forClass(List.class);
        verify(store).replaceFileInventory(captured.capture());
        var entries=captured.getValue();
        assertEquals(3,entries.size());
        assertEquals(2,entries.stream().filter(e->e.sha256().equals(entries.getFirst().sha256())).count());
        service.close();
    }

    @Test void reportCalculatesDuplicateCopiesAndReclaimableBytes() throws Exception {
        var store=mock(ArchiveStore.class);
        when(store.fileInventory()).thenReturn(List.of(
                Map.of("path","a.flac","bytes",10,"modified_at",1,"sha256","same","scanned_at",2),
                Map.of("path","b.flac","bytes",10,"modified_at",1,"sha256","same","scanned_at",2),
                Map.of("path","c.flac","bytes",10,"modified_at",1,"sha256","same","scanned_at",2),
                Map.of("path","d.mp3","bytes",7,"modified_at",1,"sha256","other","scanned_at",2)));
        var service=new DuplicateFileService(store,new MediaFiles(root.toString(),"ffprobe","ffmpeg",new ObjectMapper()),false);
        var report=service.report();
        assertEquals(1,report.get("duplicateGroups"));
        assertEquals(2L,report.get("duplicateFiles"));
        assertEquals(20L,report.get("reclaimableBytes"));
        assertEquals(2L,report.get("completedAt"));
        service.close();
    }
}
