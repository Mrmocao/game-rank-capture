package com.youkia.iaa.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.youkia.iaa.config.WorkspaceAccess;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.Map;

@RestController @RequestMapping("/api/v1/access")
public class WorkspaceAccessController {
    private final WorkspaceAccess access;
    public WorkspaceAccessController(WorkspaceAccess access){this.access=access;}
    @GetMapping public ApiResponse<?> state(HttpServletRequest request){return ApiResponse.of(Map.of("enabled",access.enabled(),"authenticated",access.authenticated(request)));}
    @PostMapping public ResponseEntity<?> login(@RequestBody JsonNode body,HttpServletRequest request){
        String session=access.login(body.path("token").asText(),request.getRemoteAddr());if(session==null)return ResponseEntity.status(401).body(Map.of("error",Map.of("message","访问口令无效")));
        var cookie=ResponseCookie.from("iaa_session",session).httpOnly(true).secure(request.isSecure()).sameSite("Strict").path("/").maxAge(Duration.ofHours(8)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie.toString()).body(ApiResponse.of(Map.of("authenticated",true)));
    }
}
