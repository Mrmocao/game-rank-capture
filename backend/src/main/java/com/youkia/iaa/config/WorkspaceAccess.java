package com.youkia.iaa.config;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Single-organization access gate. Tenant is server-owned; cookies are opaque, expiring sessions. */
@Component
public class WorkspaceAccess extends OncePerRequestFilter {
    private final String token,tenant;private final ObjectMapper mapper;
    @Value("${CAPTURE_REMOTE_METRIC_TOKEN:}") private String metricToken="";
    private final Map<String,Instant> sessions=new ConcurrentHashMap<>();
    private final Map<String,Attempt> attempts=new ConcurrentHashMap<>();
    private record Attempt(int count,Instant until){}
    public WorkspaceAccess(@Value("${PROFILE_ACCESS_TOKEN:}") String token,@Value("${PROFILE_TENANT_ID:default}") String tenant,ObjectMapper mapper){this.token=token;this.tenant=tenant;this.mapper=mapper;}
    public boolean enabled(){return !token.isBlank();}
    public boolean authenticated(HttpServletRequest request){
        if(!enabled())return true;
        String bearer=request.getHeader("Authorization");if(bearer!=null&&bearer.startsWith("Bearer ")&&equalsSecret(bearer.substring(7),token))return true;
        if(request.getCookies()!=null)for(var cookie:request.getCookies())if(cookie.getName().equals("iaa_session")){var expiry=sessions.get(cookie.getValue());if(expiry!=null&&expiry.isAfter(Instant.now()))return true;}
        return false;
    }
    public String login(String supplied,String address){
        var attempt=attempts.get(address);if(attempt!=null&&attempt.count()>=10&&attempt.until().isAfter(Instant.now()))throw new IllegalArgumentException("尝试过多，请15分钟后重试");
        if(!enabled()||!equalsSecret(supplied,token)){attempts.compute(address,(k,v)->v==null||v.until().isBefore(Instant.now())?new Attempt(1,Instant.now().plusSeconds(900)):new Attempt(v.count()+1,v.until()));return null;}
        attempts.remove(address);sessions.entrySet().removeIf(e->e.getValue().isBefore(Instant.now()));byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String id=HexFormat.of().formatHex(bytes);sessions.put(id,Instant.now().plusSeconds(8*3600));return id;
    }
    private boolean equalsSecret(String a,String b){return a!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(request.getRequestURI().startsWith("/api/")&&!request.getRequestURI().equals("/api/v1/access")){
            String requestedTenant=request.getHeader("X-Tenant-Id");
            if(requestedTenant!=null&&!requestedTenant.equals(tenant)){deny(response,403,"TENANT_FORBIDDEN","组织范围由服务器限定");return;}
            if(!authenticated(request)){deny(response,401,"WORKSPACE_LOGIN_REQUIRED","请先输入工作区访问口令");return;}
            boolean metricResult=request.getMethod().equals("POST")&&request.getRequestURI().equals("/api/v1/admin/capture-workspace/metrics/remote/result");
            if(metricResult&&(metricToken.isBlank()||!equalsSecret(request.getHeader("X-Capture-Metrics-Token"),metricToken))){deny(response,401,"METRIC_TOKEN_REQUIRED","指标回传认证无效");return;}
            long limit=metricResult?5_400_000:262144;
            if(request.getContentLengthLong()>limit){deny(response,413,"MATERIAL_TOO_LARGE",metricResult?"指标原文请求超过限制":"材料请求不得超过256KB");return;}
        }
        chain.doFilter(request,response);
    }
    private void deny(HttpServletResponse response,int status,String code,String message)throws IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");mapper.writeValue(response.getWriter(),Map.of("error",Map.of("code",code,"message",message)));}
}
