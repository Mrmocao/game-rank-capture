package com.youkia.capture.web;

import com.youkia.capture.CaptureException;
import java.net.*;
import java.util.*;

/** Public HTTPS only. Applied to user targets, provider endpoints and returned final URLs. */
public final class PublicWebUrl {
    private PublicWebUrl(){}
    public static URI syntax(String raw) {
        try {
            URI u=URI.create(raw);
            if(raw.length()>1000||!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||(u.getPort()!=-1&&u.getPort()!=443)||u.getHost().endsWith(".")||u.getHost().contains(":"))throw new IllegalArgumentException();
            String host=u.getHost().toLowerCase(Locale.ROOT);
            if(!host.contains(".")||host.endsWith(".local")||host.endsWith(".internal")||host.endsWith(".localhost")||host.endsWith(".test"))throw new IllegalArgumentException();
            if(u.getRawQuery()!=null)for(String pair:u.getRawQuery().split("&")) {
                String key=URLDecoder.decode(pair.split("=",2)[0],java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if(key.matches(".*(token|secret|password|passwd|api.?key|auth|cookie|signature|session).*"))throw new IllegalArgumentException();
            }
            return u;
        } catch(Exception e){throw new CaptureException("PUBLIC_URL_REQUIRED","仅支持不含凭证的公开HTTPS网址；不支持内网、非443端口或敏感查询参数");}
    }
    public static URI check(String raw) {
        URI uri=syntax(raw);
        try {
            var addresses=InetAddress.getAllByName(uri.getHost());
            if(addresses.length==0)throw new UnknownHostException();
            for(var a:addresses)if(!publicAddress(a))throw new CaptureException("PRIVATE_ADDRESS_BLOCKED","不允许抓取或连接私网、回环及保留地址");
            return uri;
        }catch(CaptureException e){throw e;}catch(Exception e){throw new CaptureException("DNS_FAILED","网站域名无法解析");}
    }
    static boolean publicAddress(InetAddress a) {
        if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return false;
        byte[] b=a.getAddress();int first=b[0]&255;
        if(b.length==4){int second=b[1]&255;return first!=0&&first!=127&&first<224&&!(first==100&&second>=64&&second<=127)&&!(first==198&&(second==18||second==19))&&!(first==192&&(second==0||second==2))&&!(first==198&&second==51)&&!(first==203&&second==0);}
        return (first&0xe0)==0x20 && !((b[0]&255)==0x20&&(b[1]&255)==0x01&&(b[2]&255)==0x0d&&(b[3]&255)==0xb8);
    }
}
