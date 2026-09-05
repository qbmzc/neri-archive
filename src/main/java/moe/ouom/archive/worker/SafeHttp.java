package moe.ouom.archive.worker;

import java.net.*;
import java.io.*;
import java.nio.file.*;

/** Media requests never carry account cookies. Every redirect is validated. */
public class SafeHttp {
    public static URI validate(String value) throws IOException {
        URI uri;
        try { uri=URI.create(value); } catch(IllegalArgumentException e) { throw new IOException("资源地址格式无效"); }
        String host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null||!(uri.getScheme().equals("https")||uri.getScheme().equals("http"))||
                (uri.getPort()!=-1&&uri.getPort()!=443&&uri.getPort()!=80)||
                !(host.equals("music.126.net")||host.endsWith(".music.126.net")||host.equals("music.163.com")||host.endsWith(".music.163.com")))
            throw new IOException("资源地址不在网易云媒体域名范围内");
        if(uri.getScheme().equals("http")) {
            try { return new URI("https",null,host,-1,uri.getPath(),uri.getQuery(),null); }
            catch(URISyntaxException e) { throw new IOException("资源地址格式无效"); }
        }
        return uri;
    }
    public HttpURLConnection open(String url,long offset) throws IOException {
        URI uri=validate(url);
        for(int i=0;i<6;i++) {
            var c=(HttpURLConnection)uri.toURL().openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept-Encoding","identity");
            c.setRequestProperty("User-Agent","NeriArchive/0.1");
            if(offset>0) c.setRequestProperty("Range","bytes="+offset+"-");
            int status=c.getResponseCode();
            if(status>=300&&status<400) {
                String location=c.getHeaderField("Location"); c.disconnect();
                if(location==null) throw new IOException("媒体重定向缺少地址");
                uri=validate(uri.resolve(location).toString()); continue;
            }
            return c;
        }
        throw new IOException("媒体重定向次数过多");
    }
    public void smallFile(String url,Path target,int maxBytes) throws IOException {
        var c=open(url,0);
        try {
            if(c.getResponseCode()!=200) throw new IOException("附属文件下载失败");
            try(var in=c.getInputStream()) {
                byte[] bytes=in.readNBytes(maxBytes+1);
                if(bytes.length>maxBytes) throw new IOException("附属文件过大");
                Files.write(target,bytes);
            }
        } finally { c.disconnect(); }
    }
}
