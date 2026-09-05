package moe.ouom.archive.netease;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;

@Component
public class NeteaseGateway implements MusicGateway {
    private final ObjectMapper mapper;
    private final CredentialVault vault;
    private Session active;
    private Session pending;
    private String qrKey;
    private long qrExpires;
    private long lastQrPoll;
    private static class Session {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(15)).build();
        Session(String cookie) {
            for(String item:(cookie+"; os=pc; appver=8.10.35").split(";")) {
                String[] kv=item.trim().split("=",2);
                if(kv.length==2 && kv[0].matches("[A-Za-z0-9_-]+") && !kv[1].contains("\n") && !kv[1].contains("\r")) put(kv[0],kv[1]);
            }
        }
        void put(String name,String value) {
            var c=new HttpCookie(name,value); c.setDomain(".music.163.com"); c.setPath("/"); c.setVersion(0);
            cookies.getCookieStore().add(URI.create("https://music.163.com"),c);
        }
        String cookieString() { return cookies.getCookieStore().getCookies().stream().filter(c->!c.hasExpired()).map(c->c.getName()+"="+c.getValue()).collect(Collectors.joining("; ")); }
    }
    public NeteaseGateway(ObjectMapper mapper,CredentialVault vault) throws Exception {
        this.mapper=mapper; this.vault=vault; active=new Session(vault.load());
    }
    private JsonNode call(Session session,String path,Map<String,?> params,boolean eapi) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>(params);
        String csrf=session.cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("__csrf")).map(HttpCookie::getValue).findFirst().orElse("");
        payload.put("csrf_token",csrf);
        var form=eapi ? NeteaseCrypto.eapi("/api"+path,mapper.writeValueAsString(payload)) : NeteaseCrypto.weapi(mapper.writeValueAsString(payload));
        String body=form.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        URI uri=URI.create((eapi?"https://interface.music.163.com/eapi":"https://music.163.com/weapi")+path);
        var req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Content-Type","application/x-www-form-urlencoded")
                .header("Referer","https://music.163.com/").header("Origin","https://music.163.com")
                .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var res=session.http.send(req,HttpResponse.BodyHandlers.ofInputStream());
        try(var in=res.body()) {
            if(res.statusCode()==401) throw new AuthRequired();
            if(res.statusCode()!=200) throw new IOException("网易云接口 HTTP "+res.statusCode());
            byte[] bytes=in.readNBytes(16*1024*1024+1);
            if(bytes.length>16*1024*1024) throw new IOException("网易云响应过大");
            res.headers().firstValue("x-refresh-token").filter(s->!s.isBlank()).ifPresent(s->session.put("MUSIC_U",s));
            JsonNode json=mapper.readTree(bytes);
            if(json==null) throw new IOException("网易云响应为空");
            if(json.path("code").asInt()==301) throw new AuthRequired();
            return json;
        }
    }
    private JsonNode account(Session s) throws Exception { return call(s,"/w/nuser/account/get",Map.of("noCheckToken",true),false); }
    public synchronized JsonNode account() throws Exception {
        JsonNode result=account(active);
        if(result.path("account").path("id").asLong()==0) throw new AuthRequired();
        vault.save(active.cookieString()); return result.path("profile");
    }
    public synchronized void importCookie(String cookie) throws Exception {
        if(cookie.length()>32768 || !cookie.contains("MUSIC_U=")) throw new IllegalArgumentException("请输入包含 MUSIC_U 的网易云 Cookie");
        Session candidate=new Session(cookie);
        if(account(candidate).path("account").path("id").asLong()==0) throw new AuthRequired();
        vault.save(candidate.cookieString()); active=candidate;
    }
    public synchronized void logout() throws Exception { vault.save(""); active=new Session(""); pending=null; qrKey=null; }
    public synchronized Map<String,Object> qrCreate() throws Exception {
        pending=new Session("");
        JsonNode json=call(pending,"/login/qrcode/unikey",Map.of("type",1,"noCheckToken",true),false);
        String key=json.path("unikey").asText(json.path("data").path("unikey").asText());
        if(key.isBlank()) throw new IOException("扫码入口暂不可用，请使用 Cookie 登录");
        qrKey=key; qrExpires=System.currentTimeMillis()+180_000; lastQrPoll=0;
        String url="https://music.163.com/login?codekey="+URLEncoder.encode(key,StandardCharsets.UTF_8);
        var matrix=new MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,240,240);
        var out=new ByteArrayOutputStream(); MatrixToImageWriter.writeToStream(matrix,"PNG",out);
        return Map.of("image","data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray()),"expiresAt",qrExpires);
    }
    public synchronized Map<String,Object> qrCheck() throws Exception {
        long now=System.currentTimeMillis();
        if(pending==null||now>qrExpires) return Map.of("code",800,"message","二维码已过期");
        if(now-lastQrPoll<2500) return Map.of("code",801,"message","等待扫码");
        lastQrPoll=now;
        JsonNode res=call(pending,"/login/qrcode/client/login",Map.of("type",1,"key",qrKey,"noCheckToken",true),false);
        int code=res.path("code").asInt();
        if(code==803) {
            if(account(pending).path("account").path("id").asLong()==0) throw new AuthRequired();
            vault.save(pending.cookieString()); active=pending; pending=null;
        }
        return Map.of("code",code,"message",res.path("message").asText(code==803?"登录成功":"等待扫码确认"));
    }
    @Override public synchronized Playlist playlist(long id) throws Exception {
        JsonNode res=call(active,"/v6/playlist/detail",Map.of("id",id,"n",100000,"s",0),false);
        if(res.path("code").asInt()!=200) throw new Unavailable("歌单不可访问，请检查 ID 或账号权限");
        JsonNode p=res.path("playlist");
        List<Long> ids=new ArrayList<>();
        if(!p.path("trackIds").isArray()) throw new IOException("歌单未返回完整 ID 列表，保留旧快照");
        p.path("trackIds").forEach(t->ids.add(t.path("id").asLong()));
        validateIds(ids,p.path("trackCount").asInt(-1));
        Map<Long,Track> tracks=new HashMap<>();
        p.path("tracks").forEach(t->tracks.put(t.path("id").asLong(),track(t)));
        List<Long> missing=ids.stream().filter(t->!tracks.containsKey(t)).toList();
        for(int i=0;i<missing.size();i+=200) {
            var batch=missing.subList(i,Math.min(i+200,missing.size()));
            String c=mapper.writeValueAsString(batch.stream().map(n->Map.of("id",n)).toList());
            JsonNode detail=call(active,"/v3/song/detail",Map.of("c",c,"ids",mapper.writeValueAsString(batch)),false);
            if(detail.path("code").asInt()!=200) throw new IOException("歌曲详情获取失败，保留旧快照");
            detail.path("songs").forEach(t->tracks.put(t.path("id").asLong(),track(t)));
        }
        if(ids.stream().anyMatch(n->!tracks.containsKey(n))) throw new IOException("歌曲详情不完整，保留旧快照");
        return new Playlist(id,p.path("name").asText("歌单 "+id),ids.stream().map(tracks::get).toList());
    }
    public static void validateIds(List<Long> ids,int count) throws IOException {
        if(count<0 || ids.size()!=count || ids.stream().anyMatch(n->n<=0) || new HashSet<>(ids).size()!=ids.size())
            throw new IOException("歌单 ID 列表不完整，保留旧快照");
    }
    private Track track(JsonNode t) {
        List<String> artists=new ArrayList<>(); t.path("ar").forEach(a->artists.add(a.path("name").asText()));
        return new Track(t.path("id").asLong(),t.path("name").asText("未知歌曲"),String.join(" / ",artists),
                t.path("al").path("name").asText("未知专辑"),t.path("al").path("picUrl").asText(""),t.path("dt").asLong(),t.path("no").asInt());
    }
    @Override public synchronized JsonNode resource(long id,String level) throws Exception {
        return call(active,"/song/enhance/player/url/v1",Map.of("ids","["+id+"]","level",level,"encodeType","flac"),true);
    }
    @Override public synchronized JsonNode lyrics(long id) throws Exception {
        return call(active,"/song/lyric",Map.of("id",id,"lv",-1,"tv",-1,"rv",-1),false);
    }
}
