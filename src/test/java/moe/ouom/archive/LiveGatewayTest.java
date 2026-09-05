package moe.ouom.archive;

import moe.ouom.archive.netease.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in, read-only public API smoke test; no user session or download. */
@EnabledIfEnvironmentVariable(named="NERI_LIVE_SMOKE",matches="true")
class LiveGatewayTest {
    @TempDir Path temp;
    @Test void publicPlaylistCanBeReadCompletely() throws Exception {
        var gateway=new NeteaseGateway(new ObjectMapper(),new CredentialVault(temp.toString()));
        var playlist=gateway.playlist(3778678);
        assertFalse(playlist.tracks().isEmpty());
        var response=gateway.resource(playlist.tracks().getFirst().id(),"hires");
        assertTrue(response.has("code"));
        System.out.println("LIVE_SMOKE playlist tracks="+playlist.tracks().size()+", resource response code="+response.path("code").asInt());
    }
}
