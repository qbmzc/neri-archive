package moe.ouom.archive.worker;

import com.fasterxml.jackson.databind.*;
import moe.ouom.archive.netease.MusicGateway;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QualitySelectorTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode response(String level) throws Exception { return json.readTree("{\"code\":200,\"data\":[{\"code\":200,\"url\":\"https://m1.music.126.net/a\",\"level\":\""+level+"\",\"type\":\"flac\",\"size\":100}]}"); }
    @Test void silentDowngradeDoesNotHideAnAvailableHigherCandidate() throws Exception {
        List<String> calls=new ArrayList<>();
        MusicGateway api=new FakeGateway() {
            public JsonNode resource(long id,String level) throws Exception { calls.add(level); return response(level.equals("hires")?"hires":"lossless"); }
        };
        var result=new QualitySelector().resolve(api,1,"FIDELITY");
        assertEquals("hires",result.actual()); assertEquals(List.of("jymaster","hires"),calls);
    }
    @Test void previewAndNullStringAreNotDownloadable() throws Exception {
        assertNull(QualitySelector.parse(json.readTree("{\"code\":200,\"data\":{\"url\":\"https://m1.music.126.net/a\",\"freeTrialInfo\":{}}}"),"hires"));
        assertNull(QualitySelector.parse(json.readTree("{\"code\":200,\"data\":{\"url\":\"null\"}}"),"hires"));
    }
    @Test void authAndNetworkFailureDoNotTriggerQualityDowngrade() throws Exception {
        assertThrows(MusicGateway.AuthRequired.class,()->QualitySelector.parse(json.readTree("{\"code\":301}"),"hires"));
        assertThrows(IOException.class,()->QualitySelector.parse(json.readTree("{\"code\":429}"),"hires"));
        assertThrows(IOException.class,()->new QualitySelector().resolve(new FakeGateway(){ public JsonNode resource(long id,String l) throws Exception { throw new IOException("offline"); }},1,"FIDELITY"));
    }
    @Test void unknownQualityNeverReplacesKnownQuality() {
        assertFalse(QualitySelector.better("unknown","lossless","FIDELITY"));
        assertFalse(QualitySelector.better("sky","hires","FIDELITY"));
        assertTrue(QualitySelector.better("sky","hires","SURROUND"));
    }
    abstract static class FakeGateway implements MusicGateway {
        public Playlist playlist(long id) { throw new UnsupportedOperationException(); }
        public JsonNode lyrics(long id) { return new ObjectMapper().createObjectNode(); }
    }
}
