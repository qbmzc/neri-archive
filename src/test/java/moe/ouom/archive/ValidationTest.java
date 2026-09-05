package moe.ouom.archive;

import moe.ouom.archive.api.ArchiveController;
import moe.ouom.archive.netease.*;
import moe.ouom.archive.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    @TempDir Path root;
    @Test void playlistUrlsAndCompleteIds() throws Exception {
        assertEquals(123,ArchiveController.playlistId("https://music.163.com/#/playlist?id=123"));
        assertEquals(123,ArchiveController.playlistId("123"));
        assertThrows(IllegalArgumentException.class,()->ArchiveController.playlistId("https://evil.test/playlist?id=123"));
        assertThrows(Exception.class,()->NeteaseGateway.validateIds(List.of(1L),2));
        assertThrows(Exception.class,()->NeteaseGateway.validateIds(List.of(1L,1L),2));
        NeteaseGateway.validateIds(List.of(),0);
    }
    @Test void mediaHostsCannotEscapeToArbitraryServices() throws Exception {
        assertEquals("https",SafeHttp.validate("http://m801.music.126.net/song.flac").getScheme());
        for(String uri:List.of("http://127.0.0.1/song","https://music.126.net.evil.test/a","file:///etc/passwd","https://user@music.126.net/a","https://music.126.net:444/a")) assertThrows(Exception.class,()->SafeHttp.validate(uri));
    }
    @Test void incompleteAudioAndUnsafeNamesAreRejected() {
        assertThrows(Exception.class,()->MediaFiles.validateDuration(30,180000));
        assertThrows(Exception.class,()->MediaFiles.validateDuration(0,180000));
        assertDoesNotThrow(()->MediaFiles.validateDuration(180.4,180000));
        assertEquals("_CON",MediaFiles.filename("CON")); assertFalse(MediaFiles.filename("../a:b").contains("/"));
    }
    @Test void vaultSurvivesRestartAndRejectsWrongKey() throws Exception {
        var vault=new CredentialVault(root.toString()); vault.save("MUSIC_U=secret-value");
        assertEquals("MUSIC_U=secret-value",new CredentialVault(root.toString()).load());
        assertFalse(new String(Files.readAllBytes(root.resolve("credentials.enc"))).contains("secret-value"));
        Files.write(root.resolve("credentials.key"),new byte[32]);
        assertThrows(Exception.class,()->new CredentialVault(root.toString()).load());
    }
    @Test void cryptoHasExpectedProtocolShape() throws Exception {
        var weapi=NeteaseCrypto.weapi("{\"id\":1}");
        assertEquals(256,weapi.get("encSecKey").length()); assertTrue(weapi.get("encSecKey").matches("[a-f0-9]+"));
        assertTrue(NeteaseCrypto.eapi("/api/song/test","{}").get("params").matches("[A-F0-9]+"));
    }
}
