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
    @Test void keepsExistingFileWhenIncomingQualityIsNotBetter() throws Exception {
        // 新下载的内容字节数更大，但实测音质更差（有损 vs 无损）——必须保留已有文件。
        exerciseSelection(false,false);
    }
    @Test void replacesSupersededFileAndMovesItToTrash() throws Exception {
        // 新文件字节数更小，但实测音质更好——必须换新，且旧文件进回收站而不是被删除。
        exerciseSelection(true,false);
    }
    @Test void databaseFailurePreservesPreviousFile() throws Exception {
        exerciseSelection(true,true);
    }
    void exerciseSelection(boolean incomingIsBetter,boolean failCommit) throws Exception {
        var mapper=new ObjectMapper();
        var media=spy(files);
        var gateway=mock(moe.ouom.archive.netease.MusicGateway.class);
        String old="Artist/Album/Song [11]-abcdefabcdef.flac";
        Files.createDirectories(files.safe(old).getParent());
        Files.writeString(files.safe(old),"old audio content");
        var song=Map.<String,Object>of("id",11,"name","Song","artist","Artist","album","Album","path",old,"level","lossless","duration",3000);
        when(store.song(11)).thenReturn(song);
        when(gateway.resource(eq(11L),anyString())).thenReturn(mapper.readTree("{\"code\":200,\"data\":{\"url\":\"https://example.com/audio\",\"level\":\"jymaster\",\"size\":1000}}"));
        when(gateway.lyrics(11)).thenReturn(mapper.createObjectNode());
        var lossless=new MediaFiles.Probe("flac",3,96000,24,900000);
        var lossy=new MediaFiles.Probe("mp3",3,44100,0,320000);
        var incomingProbe=incomingIsBetter?lossless:lossy;
        var existingProbe=incomingIsBetter?lossy:lossless;
        // songs 行没有采样规格，因此已有文件的音质通过 ffprobe 探测获得。
        doAnswer(invocation -> files.safe(old).equals(invocation.getArgument(0))?existingProbe:incomingProbe)
                .when(media).probe(any(Path.class),anyLong());
        doAnswer(i->i.getArgument(0)).when(media).tag(any(),any(),anyMap());
        if(failCommit) doThrow(new IllegalStateException("database failure")).when(store).complete(anyLong(),anyLong(),anyString(),anyString(),anyLong(),anyString(),anyInt(),anyInt(),anyInt(),anyString());
        String body=incomingIsBetter?"123456":"x".repeat(1000);
        var worker=new DownloadWorker(store,gateway,media,mapper,new SafeHttp()) {
            @Override void transfer(long id,QualitySelector.Resource resource,Path part,Path signature) throws Exception { Files.writeString(part,body); }
        };
        worker.run(Map.of("id",1,"song_id",11,"policy","FIDELITY","attempts",1));
        if(incomingIsBetter&&!failCommit) {
            assertFalse(Files.exists(files.safe(old)));
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String archived=files.relative(song,"flac",hash);
            assertEquals(body,Files.readString(media.safe(archived)));
            verify(store).complete(eq(1L),eq(11L),eq(archived),eq("jymaster"),eq((long)body.length()),eq(hash),eq(96000),eq(24),eq(900000),eq(""));
            // 被替换的文件进回收站，内容完整可恢复。
            List<Path> trashed=trashEntries();
            assertEquals(1,trashed.size());
            assertEquals("old audio content",Files.readString(trashed.getFirst()));
            assertTrue(trashed.getFirst().startsWith(files.trash.resolve("11")));
        } else if(failCommit) {
            assertEquals("old audio content",Files.readString(files.safe(old)));
            assertTrue(trashEntries().isEmpty());
        } else {
            assertEquals("old audio content",Files.readString(files.safe(old)));
            verify(store).complete(eq(1L),eq(11L),eq(old),eq("lossless"),eq(Files.size(files.safe(old))),
                    eq(MediaFiles.hash(files.safe(old),"SHA-256")),eq(96000),eq(24),eq(900000),eq(""));
            assertTrue(trashEntries().isEmpty());
        }
    }
    @Test void skipsTransferWhenServerReturnsNoBetterLevel() throws Exception {
        var mapper=new ObjectMapper();
        var gateway=mock(moe.ouom.archive.netease.MusicGateway.class);
        String old="Artist/Album/Song [11]-abcdefabcdef.flac";
        Files.createDirectories(files.safe(old).getParent());
        Files.writeString(files.safe(old),"old audio content");
        var song=Map.<String,Object>of("id",11,"name","Song","artist","Artist","album","Album","path",old,"level","jymaster","duration",3000);
        when(store.song(11)).thenReturn(song);
        when(gateway.resource(eq(11L),anyString())).thenReturn(mapper.readTree("{\"code\":200,\"data\":{\"url\":\"https://example.com/audio\",\"level\":\"jymaster\",\"size\":1000}}"));
        var worker=new DownloadWorker(store,gateway,files,mapper,new SafeHttp()) {
            @Override void transfer(long id,QualitySelector.Resource resource,Path part,Path signature) { fail("档位没有提升时不应发生传输"); }
        };
        worker.run(Map.of("id",1,"song_id",11,"policy","FIDELITY","attempts",1,"forced",0));
        verify(store).outcome(eq(1L),eq("SKIPPED"),org.mockito.ArgumentMatchers.contains("jymaster"),eq(0L));
        verify(store,never()).complete(anyLong(),anyLong(),anyString(),anyString(),anyLong(),anyString(),anyInt(),anyInt(),anyInt(),anyString());
        assertEquals("old audio content",Files.readString(files.safe(old)));
        assertTrue(trashEntries().isEmpty());
    }
    @Test void forcedTaskBypassesTheLevelComparison() throws Exception {
        var mapper=new ObjectMapper();
        var media=spy(files);
        var gateway=mock(moe.ouom.archive.netease.MusicGateway.class);
        String old="Artist/Album/Song [11]-abcdefabcdef.flac";
        Files.createDirectories(files.safe(old).getParent());
        Files.writeString(files.safe(old),"old audio content");
        var song=Map.<String,Object>of("id",11,"name","Song","artist","Artist","album","Album","path",old,"level","jymaster","duration",3000);
        when(store.song(11)).thenReturn(song);
        when(gateway.resource(eq(11L),anyString())).thenReturn(mapper.readTree("{\"code\":200,\"data\":{\"url\":\"https://example.com/audio\",\"level\":\"jymaster\",\"size\":6}}"));
        when(gateway.lyrics(11)).thenReturn(mapper.createObjectNode());
        doAnswer(i->new MediaFiles.Probe("flac",3,96000,24,900000)).when(media).probe(any(Path.class),anyLong());
        doAnswer(i->i.getArgument(0)).when(media).tag(any(),any(),anyMap());
        var worker=new DownloadWorker(store,gateway,media,mapper,new SafeHttp()) {
            @Override void transfer(long id,QualitySelector.Resource resource,Path part,Path signature) throws Exception { Files.writeString(part,"123456"); }
        };
        worker.run(Map.of("id",1,"song_id",11,"policy","FIDELITY","attempts",1,"forced",1));
        verify(store,never()).outcome(eq(1L),eq("SKIPPED"),anyString(),anyLong());
        verify(store).complete(anyLong(),anyLong(),anyString(),anyString(),anyLong(),anyString(),anyInt(),anyInt(),anyInt(),anyString());
    }
    @Test void unknownLevelOnEitherSideNeverTriggersASkip() throws Exception {
        // 服务未返回档位时必须放行：未知档位不得被当成「没有提升」。
        var mapper=new ObjectMapper();
        var media=spy(files);
        var gateway=mock(moe.ouom.archive.netease.MusicGateway.class);
        String old="Artist/Album/Song [11]-abcdefabcdef.flac";
        Files.createDirectories(files.safe(old).getParent());
        Files.writeString(files.safe(old),"old audio content");
        var song=Map.<String,Object>of("id",11,"name","Song","artist","Artist","album","Album","path",old,"level","jymaster","duration",3000);
        when(store.song(11)).thenReturn(song);
        when(gateway.resource(eq(11L),anyString())).thenReturn(mapper.readTree("{\"code\":200,\"data\":{\"url\":\"https://example.com/audio\",\"size\":6}}"));
        when(gateway.lyrics(11)).thenReturn(mapper.createObjectNode());
        doAnswer(i->new MediaFiles.Probe("flac",3,96000,24,900000)).when(media).probe(any(Path.class),anyLong());
        doAnswer(i->i.getArgument(0)).when(media).tag(any(),any(),anyMap());
        var worker=new DownloadWorker(store,gateway,media,mapper,new SafeHttp()) {
            @Override void transfer(long id,QualitySelector.Resource resource,Path part,Path signature) throws Exception { Files.writeString(part,"123456"); }
        };
        worker.run(Map.of("id",1,"song_id",11,"policy","FIDELITY","attempts",1,"forced",0));
        verify(store,never()).outcome(eq(1L),eq("SKIPPED"),anyString(),anyLong());
        verify(store).complete(anyLong(),anyLong(),anyString(),anyString(),anyLong(),anyString(),anyInt(),anyInt(),anyInt(),anyString());
    }
    @Test void missingLocalFileIsRedownloadedEvenWhenLevelsMatch() throws Exception {
        // 补下载场景：songs 记录的档位与即将下载的相同，但盘上文件已不存在。
        // songs.level 描述的是一个不存在的文件，不能拿它把补下载静默跳过。
        var mapper=new ObjectMapper();
        var media=spy(files);
        var gateway=mock(moe.ouom.archive.netease.MusicGateway.class);
        String old="Artist/Album/Song [11]-abcdefabcdef.flac";
        var song=Map.<String,Object>of("id",11,"name","Song","artist","Artist","album","Album","path",old,"level","jymaster","duration",3000);
        when(store.song(11)).thenReturn(song);
        when(gateway.resource(eq(11L),anyString())).thenReturn(mapper.readTree("{\"code\":200,\"data\":{\"url\":\"https://example.com/audio\",\"level\":\"jymaster\",\"size\":6}}"));
        when(gateway.lyrics(11)).thenReturn(mapper.createObjectNode());
        doAnswer(i->new MediaFiles.Probe("flac",3,96000,24,900000)).when(media).probe(any(Path.class),anyLong());
        doAnswer(i->i.getArgument(0)).when(media).tag(any(),any(),anyMap());
        var worker=new DownloadWorker(store,gateway,media,mapper,new SafeHttp()) {
            @Override void transfer(long id,QualitySelector.Resource resource,Path part,Path signature) throws Exception { Files.writeString(part,"123456"); }
        };
        worker.run(Map.of("id",1,"song_id",11,"policy","FIDELITY","attempts",1,"forced",0));
        verify(store,never()).outcome(eq(1L),eq("SKIPPED"),anyString(),anyLong());
        verify(store).complete(anyLong(),anyLong(),anyString(),anyString(),anyLong(),anyString(),anyInt(),anyInt(),anyInt(),anyString());
    }
    List<Path> trashEntries() throws Exception {
        if(!Files.isDirectory(files.trash)) return List.of();
        try(var paths=Files.walk(files.trash)) { return paths.filter(Files::isRegularFile).toList(); }
    }
    @Test void filesystemErrorsExplainTheCause() {
        assertTrue(DownloadWorker.safeError(new AccessDeniedException("/music/董贞")).contains("无写入权限"));
        assertTrue(DownloadWorker.safeError(new FileAlreadyExistsException("/music/董贞")).contains("同名文件"));
        assertTrue(DownloadWorker.safeError(new IOException("bad https://example.com/private?token=abc")).contains("[资源地址]"));
    }
    @TempDir Path root;
    ArchiveStore store;
    MediaFiles files;
    @BeforeEach void setup() throws Exception {
        store=mock(ArchiveStore.class); when(store.task(1)).thenReturn(Map.of("status","RUNNING"));
        files=new MediaFiles(root.toString(),"",7,"ffprobe","ffmpeg",new ObjectMapper());
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
