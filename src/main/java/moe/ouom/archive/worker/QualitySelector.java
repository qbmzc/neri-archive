package moe.ouom.archive.worker;

import moe.ouom.archive.netease.MusicGateway;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.io.IOException;

public class QualitySelector {
    public static final List<String> FIDELITY=List.of("jymaster","hires","lossless","exhigh","higher","standard");
    public static final List<String> SURROUND=List.of("sky","jyeffect","jymaster","hires","lossless","exhigh","higher","standard");
    public record Resource(String url,String requested,String actual,String type,long size,String md5) {}
    public static List<String> order(String policy) { return "SURROUND".equals(policy)?SURROUND:FIDELITY; }
    public static int rank(String level,String policy) { int n=order(policy).indexOf(level); return n<0?999:n; }
    public static boolean better(String candidate,String current,String policy) { return rank(candidate,policy)<rank(current,policy); }
    public Resource resolve(MusicGateway api,long songId,String policy) throws Exception {
        Resource best=null;
        for(String level:order(policy)) {
            Resource candidate=parse(api.resource(songId,level),level);
            if(candidate==null) continue;
            if(best==null||better(candidate.actual(),best.actual(),policy)) best=candidate;
            // The server can silently downgrade. Continue until all higher candidates have been tried.
            if(rank(best.actual(),policy)<=rank(level,policy)) return best;
        }
        if(best==null) throw new MusicGateway.Unavailable("没有可获取的完整音频：可能为试听、权限不足或资源不可用");
        return best;
    }
    public static Resource parse(JsonNode root,String requested) throws IOException {
        int code=root.path("code").asInt(-1);
        if(code==301||code==401) throw new MusicGateway.AuthRequired();
        if(code==429||code>=500||code==-1) throw new IOException("音质接口暂时不可用，请稍后重试");
        if(code!=200) return null;
        JsonNode data=root.path("data"); if(data.isArray()) data=data.path(0);
        int itemCode=data.path("code").asInt(200);
        if(itemCode==301||itemCode==401) throw new MusicGateway.AuthRequired();
        if(itemCode==429||itemCode>=500) throw new IOException("音质接口暂时不可用");
        if(itemCode!=200 || data.hasNonNull("freeTrialInfo")) return null;
        String url=data.path("url").asText("");
        if(url.isBlank()||url.equals("null")) return null;
        return new Resource(url,requested,data.path("level").asText("unknown"),data.path("type").asText(""),data.path("size").asLong(),data.path("md5").asText(""));
    }
}
