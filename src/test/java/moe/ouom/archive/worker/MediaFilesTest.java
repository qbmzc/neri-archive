package moe.ouom.archive.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class MediaFilesTest {
    @TempDir Path root;
    @Test void existingDirectoriesAreReusedAndOnlySameSongVersionsMatch() throws Exception {
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        var song=Map.<String,Object>of("id",1,"name","歌曲","artist","董贞","album","专辑");
        String target=files.relative(song,"flac","123456789012aaaa");
        Path directory=files.safe(target).getParent(); Files.createDirectories(directory);
        Path old=directory.resolve("歌曲 [1]-abcdefabcdef.flac"); Files.writeString(old,"old audio");
        Path plain=directory.resolve("歌曲.flac"); Files.writeString(plain,"plain audio");
        Files.writeString(directory.resolve("歌曲 [2]-abcdefabcdef.flac"),"another song");
        assertEquals(Set.of(old,plain),new HashSet<>(files.versions(song,"flac",target)));
        Path incoming=root.resolve("incoming"); Files.writeString(incoming,"new");
        MediaFiles.move(incoming,files.safe(target));
        assertEquals("new",Files.readString(files.safe(target)));
        assertTrue(Files.exists(old));
    }
    @Test void trashPathIsConfigurableAndRejectsUnsafeLayout() throws Exception {
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        assertEquals(root.resolve(".trash").toAbsolutePath().normalize(),files.trash);
        assertThrows(Exception.class,()->new MediaFiles(root.toString(),root.toString(),7,"ffprobe","ffmpeg",new ObjectMapper()));
        assertThrows(Exception.class,()->new MediaFiles(root.resolve("music").toString(),root.toString(),7,"ffprobe","ffmpeg",new ObjectMapper()));
        var external=new MediaFiles(root.resolve("music").toString(),root.resolve("elsewhere").toString(),7,"ffprobe","ffmpeg",new ObjectMapper());
        assertEquals(root.resolve("elsewhere").toAbsolutePath().normalize(),external.trash);
    }
    @Test void ignoredPathsCoverWorkDirectoryTrashAndHiddenDirectories() throws Exception {
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        Files.createDirectories(root.resolve(".work"));
        assertTrue(files.isIgnored(root.resolve(".work/unfinished.flac")));
        assertTrue(files.isIgnored(files.trash.resolve("11/20260101-000000000-old.flac")));
        Files.createDirectories(root.resolve(".hidden"));
        assertTrue(files.isIgnored(root.resolve(".hidden/song.flac")));
        assertFalse(files.isIgnored(root.resolve("Artist/Album/song.flac")));
    }
    @Test void trashMoveKeepsSourceWhenTargetIsUnwritable() throws Exception {
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        Path audio=root.resolve("Artist/song.flac"); Files.createDirectories(audio.getParent());
        Files.writeString(audio,"audio");
        assertTrue(files.moveToTrash(audio,11));
        assertFalse(Files.exists(audio));
        List<Path> trashed;
        try(var paths=Files.walk(files.trash)) { trashed=paths.filter(Files::isRegularFile).toList(); }
        assertEquals(1,trashed.size());
        assertEquals("audio",Files.readString(trashed.getFirst()));
        assertTrue(trashed.getFirst().getFileName().toString().endsWith("-song.flac"));
    }
    @Test void zeroRetentionDeletesImmediatelyWithoutTrashEntry() throws Exception {
        var files=new MediaFiles(root.toString(),"",0,"ffprobe","ffmpeg",new ObjectMapper());
        Path audio=root.resolve("Artist/song.flac"); Files.createDirectories(audio.getParent());
        Files.writeString(audio,"audio");
        assertTrue(files.moveToTrash(audio,11));
        assertFalse(Files.exists(audio));
        assertFalse(Files.exists(files.trash.resolve("11")));
    }
    @Test void cleanupTrashHonoursRetentionCutoff() throws Exception {
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        Path directory=files.trash.resolve("11"); Files.createDirectories(directory);
        long now=System.currentTimeMillis();
        Path stale=directory.resolve("20200101-000000000-old.flac"), fresh=directory.resolve("20200101-000000001-new.flac");
        Files.writeString(stale,"old"); Files.writeString(fresh,"new");
        // retention = 7 天：8 天前的被清理，1 天前的保留。
        Files.setLastModifiedTime(stale,java.nio.file.attribute.FileTime.fromMillis(now-8L*86_400_000L));
        Files.setLastModifiedTime(fresh,java.nio.file.attribute.FileTime.fromMillis(now-86_400_000L));
        assertEquals(1,files.cleanupTrash());
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
        // 空目录随之移除。
        Files.deleteIfExists(fresh);
        assertEquals(0,files.cleanupTrash());
        assertFalse(Files.exists(directory));
    }
    @Test void actualFlacProbeAndLosslessTagging() throws Exception {
        boolean available;
        try { Process p=new ProcessBuilder("ffmpeg","-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); available=p.waitFor(5,TimeUnit.SECONDS)&&p.exitValue()==0; }
        catch(Exception e) { available=false; }
        assumeTrue(available,"ffmpeg not installed; media tool integration requires ffmpeg/ffprobe");
        Path audio=root.resolve("source.flac");
        Process p=new ProcessBuilder("ffmpeg","-nostdin","-v","error","-f","lavfi","-i","sine=frequency=440:duration=3","-c:a","flac",audio.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertTrue(p.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,p.exitValue());
        var files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
        var probe=files.probe(audio,3000); assertEquals("flac",probe.extension()); assertEquals(44100,probe.rate());
        Path tagged=files.tag(audio,probe,Map.of("id",1,"name","测试音乐","artist","测试歌手","album","测试专辑","track_no",1));
        assertEquals(probe.rate(),files.probe(tagged,3000).rate());
        assertEquals(decodedHash(audio),decodedHash(tagged));
        assertThrows(Exception.class,()->files.probe(tagged,180000));
    }
    String decodedHash(Path file) throws Exception {
        Process p=new ProcessBuilder("ffmpeg","-v","error","-i",file.toString(),"-map","0:a","-f","hash","-").start();
        String hash=new String(p.getInputStream().readAllBytes()); assertTrue(p.waitFor(10,TimeUnit.SECONDS)); assertEquals(0,p.exitValue()); return hash;
    }
}
