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
    @Test void actualFlacProbeAndLosslessTagging() throws Exception {
        boolean available;
        try { Process p=new ProcessBuilder("ffmpeg","-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); available=p.waitFor(5,TimeUnit.SECONDS)&&p.exitValue()==0; }
        catch(Exception e) { available=false; }
        assumeTrue(available,"ffmpeg not installed; media tool integration requires ffmpeg/ffprobe");
        Path audio=root.resolve("source.flac");
        Process p=new ProcessBuilder("ffmpeg","-nostdin","-v","error","-f","lavfi","-i","sine=frequency=440:duration=3","-c:a","flac",audio.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertTrue(p.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,p.exitValue());
        var files=new MediaFiles(root.toString(),"ffprobe","ffmpeg",new ObjectMapper());
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
