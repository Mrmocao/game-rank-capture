package com.youkia.iaa.api;

import com.youkia.iaa.collection.capture.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController @RequestMapping("/api/v1/admin/capture-login")
public class CaptureLoginController {
    private final SourceSessionStatus status;private final ObjectMapper mapper;private final CaptureLoginAccess access;
    private final String origin;private final Path statePath;private final boolean enabled;
    public CaptureLoginController(SourceSessionStatus status,ObjectMapper mapper,
        @Value("${CAPTURE_LOGIN_ACCESS_CODE:}") String code,@Value("${CAPTURE_LOGIN_PUBLIC_ORIGIN:}") String origin,
        @Value("${CAPTURE_LOGIN_STATE:/opt/iaa-radar/shared/deliveries/web-login.json}") String state,
        @Value("${CAPTURE_LOGIN_ENABLED:false}") boolean enabled){
        this.status=status;this.mapper=mapper;this.access=new CaptureLoginAccess(code,Clock.systemUTC());
        this.origin=origin;this.statePath=Path.of(state);this.enabled=enabled;
    }
    private JsonNode state(){try{if(Files.size(statePath)<8192)return mapper.readTree(Files.readString(statePath));}catch(Exception ignored){}return mapper.createObjectNode().put("state","CLOSED");}
    private boolean configured(){return enabled&&access.configured()&&origin.matches("https://[a-zA-Z0-9.-]+(?::[0-9]+)?");}
    private String cookie(HttpServletRequest r){if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("capture_login"))return c.getValue();return null;}
    private boolean secure(HttpServletRequest r){return r.isSecure()||("https".equals(r.getHeader("X-Forwarded-Proto"))&&r.getRemoteAddr().equals("127.0.0.1"));}
    private void transport(HttpServletRequest r){
        if(!configured())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"LOGIN_NOT_CONFIGURED","网页续登尚未配置");
        if(!secure(r)||!origin.equals(r.getHeader("Origin")))throw new ApiException(HttpStatus.FORBIDDEN,"LOGIN_ORIGIN_REQUIRED","请从配置的 HTTPS 站点进入续登");
    }
    @GetMapping("/status") public ApiResponse<?> status(HttpServletRequest r,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.of(Map.of("source",status.read(),"configured",configured(),"publicOrigin",origin,
            "authenticated",access.valid(cookie(r)),"window",state()));
    }
    @PostMapping("/access") public ResponseEntity<?> login(@RequestBody JsonNode body,HttpServletRequest r){
        transport(r);String token=access.login(body.path("code").asText());
        if(token==null)throw new ApiException(HttpStatus.FORBIDDEN,"LOGIN_CODE_INVALID","续登管理口令无效或尝试过多，请稍后重试");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.SET_COOKIE,
            ResponseCookie.from("capture_login",token).secure(true).httpOnly(true).sameSite("Strict").path("/").maxAge(Duration.ofMinutes(30)).build().toString()).body(ApiResponse.of(Map.of("authenticated",true)));
    }
    @PostMapping("/{action:start|finish|cancel}") public synchronized ApiResponse<?> action(@PathVariable String action,HttpServletRequest r) throws Exception{
        transport(r);if(!access.valid(cookie(r)))throw new ApiException(HttpStatus.FORBIDDEN,"LOGIN_ACCESS_REQUIRED","请先输入续登管理口令");
        String current=state().path("state").asText();
        if(Set.of("OPENING","VERIFYING").contains(current))throw new ApiException(HttpStatus.CONFLICT,"LOGIN_BUSY","正在处理登录窗口，请等待状态更新");
        // Fixed service names only. No user-controlled command, source URL or profile path.
        var p=new ProcessBuilder("sudo","-n","/usr/bin/systemctl","start","--no-block","iaa-radar-web-login-"+action+".service").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if(!p.waitFor(5,TimeUnit.SECONDS)||p.exitValue()!=0){p.destroyForcibly();throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"LOGIN_SERVICE_FAILED","续登服务未能启动，请联系维护人员");}
        return ApiResponse.of(Map.of("accepted",true,"action",action));
    }
    /** Nginx internal auth_request protects every noVNC asset and websocket upgrade. */
    @GetMapping("/viewer-auth") public ResponseEntity<Void> viewer(HttpServletRequest r){
        var s=state();boolean fresh=false;try{fresh=Instant.parse(s.path("expiresAt").asText()).isAfter(Instant.now());}catch(Exception ignored){}
        boolean sameOrigin=r.getHeader("Origin")==null||origin.equals(r.getHeader("Origin"));
        return ResponseEntity.status(configured()&&secure(r)&&sameOrigin&&access.valid(cookie(r))&&"READY".equals(s.path("state").asText())&&fresh?204:403).header(HttpHeaders.CACHE_CONTROL,"no-store").build();
    }
}
