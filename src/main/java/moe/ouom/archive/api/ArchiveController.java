package moe.ouom.archive.api;

import moe.ouom.archive.store.ArchiveStore;
import moe.ouom.archive.netease.*;
import moe.ouom.archive.worker.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;

import java.util.*;
import java.nio.file.*;

import static moe.ouom.archive.store.ArchiveStore.*;

@RestController
public class ArchiveController {
    private final ArchiveStore store;
    private final NeteaseGateway gateway;
    private final ArchiveEngine engine;
    private final MediaFiles files;

    public ArchiveController(ArchiveStore store, NeteaseGateway gateway, ArchiveEngine engine, MediaFiles files) {
        this.store = store;
        this.gateway = gateway;
        this.engine = engine;
        this.files = files;
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("header", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/api/overview")
    public Map<String, Object> overview() throws Exception {
        var result = new LinkedHashMap<>(store.stats());
        result.put("freeBytes", Files.getFileStore(files.root).getUsableSpace());
        result.put("musicPath", files.root.toString());
        return result;
    }

    @GetMapping("/api/subscriptions")
    public Object subscriptions() {
        return store.subscriptions();
    }

    public record SubscriptionRequest(String source, int intervalMinutes, boolean initialDownload, String policy,
                                      boolean autoUpgrade) {
    }

    public static long playlistId(String source) {
        if (source == null) throw new IllegalArgumentException("请输入歌单 ID 或链接");
        String value = source.trim();
        try {
            if (value.matches("[0-9]{1,18}")) return Long.parseLong(value);
            var uri = java.net.URI.create(value);
            if (!"music.163.com".equalsIgnoreCase(uri.getHost())) throw new IllegalArgumentException();
            var match = java.util.regex.Pattern.compile("(?:[?&]id=|/playlist/)([0-9]{1,18})(?:[^0-9]|$)").matcher(value);
            if (match.find() && value.contains("playlist")) return Long.parseLong(match.group(1));
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("请输入有效歌单 ID 或 music.163.com 歌单链接");
    }

    @PostMapping("/api/subscriptions")
    public Object subscribe(@RequestBody SubscriptionRequest r) {
        long id = playlistId(r.source());
        store.subscribe(id, r.intervalMinutes(), r.initialDownload(), r.policy(), r.autoUpgrade());
        return store.subscription(id);
    }

    @PostMapping("/api/subscriptions/{id}/scan")
    public Object scan(@PathVariable long id) {
        store.due(id);
        return Map.of("message", "已安排检查");
    }

    public record Enabled(boolean enabled) {
    }

    @PatchMapping("/api/subscriptions/{id}")
    public void enabled(@PathVariable long id, @RequestBody Enabled r) {
        store.enabled(id, r.enabled());
    }

    @DeleteMapping("/api/subscriptions/{id}")
    public void delete(@PathVariable long id) {
        store.delete(id);
    }

    @GetMapping("/api/subscriptions/{id}/songs")
    public Object songs(@PathVariable long id) {
        return store.playlistSongs(id);
    }

    @GetMapping("/api/tasks")
    public Object tasks() {
        return store.tasks();
    }

    @PostMapping("/api/tasks/{id}/{action}")
    public void task(@PathVariable long id, @PathVariable String action) {
        engine.control(id, action);
    }

    @GetMapping("/api/library")
    public Object library() {
        return store.library();
    }

    @PostMapping("/api/library/repair")
    public void repair() {
        engine.repairMissing();
    }

    @PostMapping("/api/library/{id}/upgrade")
    public void upgrade(@PathVariable long id) {
        store.enqueue(id, "FIDELITY");
    }

    @GetMapping("/api/account")
    public Object account() throws Exception {
        return gateway.account();
    }

    public record CookieRequest(String cookie) {
    }

    @PostMapping("/api/account/cookie")
    public void cookie(@RequestBody CookieRequest r) throws Exception {
        if (r.cookie() == null) throw new IllegalArgumentException("Cookie 不能为空");
        gateway.importCookie(r.cookie());
        store.resumeAuth();
    }

    @DeleteMapping("/api/account")
    public void logout() throws Exception {
        gateway.logout();
    }

    @PostMapping("/api/account/qr")
    public Object qr() throws Exception {
        return gateway.qrCreate();
    }

    @PostMapping("/api/account/qr/check")
    public Object qrCheck() throws Exception {
        var result = gateway.qrCheck();
        if (Objects.equals(result.get("code"), 803)) store.resumeAuth();
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MusicGateway.AuthRequired.class)
    public ResponseEntity<?> auth(MusicGateway.AuthRequired e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage(), "code", "NETEASE_AUTH_REQUIRED"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> error(Exception e) {
        return ResponseEntity.status(502).body(Map.of("message", "操作失败，请检查网络或服务配置后重试"));
    }
}
