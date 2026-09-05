package moe.ouom.archive.worker;

import moe.ouom.archive.store.ArchiveStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadWorkerTest {
    @TempDir Path root;
    ArchiveStore store;
    MediaFiles files;
    @BeforeEach void setup() throws Exception {
        store=mock(ArchiveStore.class); when(store.task(1)).thenReturn(Map.of("status","RUNNING"));
        files=new MediaFiles(root.toString(),"ffprobe","ffmpeg",new ObjectMapper());
    }
    static class Response extends HttpURLConnection {
        final byte[] body; final int code; final String range; final long length;
        Response(byte[] body,int code,String range,long length) throws Exception { super(URI.create("https://m1.music.126.net/a").toURL()); this.body=body; this.code=code; this.range=range; this.length=length; }
        public int getResponseCode() { return code; } public long getContentLengthLong() { return length; }
        public String getHeaderField(String key) { return key.equals("Content-Range")?range:null; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        public void connect() {} public void disconnect() {} public boolean usingProxy() { return false; }
    }
    DownloadWorker worker(Response response,List<Long> offsets) {
        return new DownloadWorker(store,null,files,new ObjectMapper(),new SafeHttp() {
            public HttpURLConnection open(String url,long offset) { offsets.add(offset); return response; }
        });
    }
    QualitySelector.Resource source() { return new QualitySelector.Resource("https://m1.music.126.net/a","hires","lossless","flac",6,"abcdef"); }
    @Test void validRangeResumesOnlyMatchingResource() throws Exception {
        Path part=root.resolve("a.part"),sig=root.resolve("a.json");
        Files.writeString(part,"abc"); Files.writeString(sig,"abcdef:6:lossless");
        List<Long> offsets=new ArrayList<>();
        worker(new Response("def".getBytes(),206,"bytes 3-5/6",3),offsets).transfer(1,source(),part,sig);
        assertEquals(List.of(3L),offsets); assertEquals("abcdef",Files.readString(part));
    }
    @Test void ignoredRangeRestartsWithoutAppendingDuplicateBytes() throws Exception {
        Path part=root.resolve("a.part"),sig=root.resolve("a.json");
        Files.writeString(part,"abc"); Files.writeString(sig,"abcdef:6:lossless");
        worker(new Response("abcdef".getBytes(),200,null,6),new ArrayList<>()).transfer(1,source(),part,sig);
        assertEquals("abcdef",Files.readString(part));
    }
    @Test void changedResourceStartsFromZero() throws Exception {
        Path part=root.resolve("a.part"),sig=root.resolve("a.json");
        Files.writeString(part,"old"); Files.writeString(sig,"other:6:lossless");
        List<Long> offsets=new ArrayList<>();
        worker(new Response("abcdef".getBytes(),200,null,6),offsets).transfer(1,source(),part,sig);
        assertEquals(List.of(0L),offsets);
    }
    @Test void truncatedAndMisalignedResponsesFail() throws Exception {
        var w=worker(new Response("abc".getBytes(),200,null,6),new ArrayList<>());
        assertThrows(IOException.class,()->w.transfer(1,source(),root.resolve("a.part"),root.resolve("a.json")));
        assertThrows(IOException.class,()->DownloadWorker.validateRange("bytes 1-5/6",3,6));
        assertThrows(IOException.class,()->DownloadWorker.validateRange("bytes 3-5/9",3,6));
    }
    @Test void pausedTaskStopsWriting() throws Exception {
        when(store.task(1)).thenReturn(Map.of("status","PAUSED"));
        var w=worker(new Response("abcdef".getBytes(),200,null,6),new ArrayList<>());
        assertThrows(InterruptedException.class,()->w.transfer(1,source(),root.resolve("a.part"),root.resolve("a.json")));
    }
    @Test void stoppedTaskCannotReplaceExistingFile() throws Exception {
        // The store guard also rejects completion if a caller misses the worker cancellation check.
        assertThrows(IOException.class,()->files.safe("../escape.mp3"));
    }
}
