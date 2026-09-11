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
    @Test void upgradeableReportsWhetherThePolicyHasRoomLeft() {
        // 已是策略顶档：每周自动升级不应再为它产生任务。
        assertFalse(QualitySelector.upgradeable("jymaster","FIDELITY"));
        assertTrue(QualitySelector.upgradeable("lossless","FIDELITY"));
        assertTrue(QualitySelector.upgradeable("standard","FIDELITY"));
        // 同一档位在不同策略下的位置不同。
        assertTrue(QualitySelector.upgradeable("jymaster","SURROUND"));
        assertFalse(QualitySelector.upgradeable("sky","SURROUND"));
        // 空或未知档位保守放行，避免误判成「已到顶」而永远不再升级。
        assertTrue(QualitySelector.upgradeable("unknown","FIDELITY"));
        assertTrue(QualitySelector.upgradeable("","FIDELITY"));
        assertTrue(QualitySelector.upgradeable(null,"FIDELITY"));
    }
    @Test void onlyKnownLevelsAreEligibleForSkipping() {
        assertTrue(QualitySelector.known("lossless","FIDELITY"));
        assertFalse(QualitySelector.known("unknown","FIDELITY"));
        assertFalse(QualitySelector.known("","FIDELITY"));
        assertFalse(QualitySelector.known("sky","FIDELITY"));
        assertTrue(QualitySelector.known("sky","SURROUND"));
    }
    abstract static class FakeGateway implements MusicGateway {
        public Playlist playlist(long id) { throw new UnsupportedOperationException(); }
        public JsonNode lyrics(long id) { return new ObjectMapper().createObjectNode(); }
    }
}
