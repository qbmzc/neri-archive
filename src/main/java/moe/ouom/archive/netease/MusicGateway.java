package moe.ouom.archive.netease;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface MusicGateway {
    record Track(long id, String name, String artist, String album, String cover, long duration, int trackNo) {}
    record Playlist(long id, String name, List<Track> tracks) {}
    Playlist playlist(long id) throws Exception;
    JsonNode resource(long id, String level) throws Exception;
    JsonNode lyrics(long id) throws Exception;
    class AuthRequired extends RuntimeException { public AuthRequired() { super("网易云登录已失效，请重新登录"); } }
    class Unavailable extends RuntimeException { public Unavailable(String message) { super(message); } }
}
