package com.youkia.iaa.api;

import com.youkia.capture.CaptureException;
import com.youkia.iaa.collection.capture.PublicProfileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/capture-workspace/public-profiles")
public final class PublicProfileController {
    private final PublicProfileService profiles; private final String tenant;
    public PublicProfileController(PublicProfileService profiles, @Value("${PROFILE_TENANT_ID:default}") String tenant) { this.profiles = profiles; this.tenant = tenant; }
    @GetMapping("/candidates") public ApiResponse<?> candidates(@RequestParam long observationId, @RequestParam(defaultValue = "20") int limit) { return ApiResponse.of(profiles.candidates(tenant, observationId, limit)); }
    @PostMapping("/capture") public ApiResponse<?> capture(@RequestBody PublicProfileService.CaptureRequest request) { return ApiResponse.of(profiles.capture(tenant, request)); }
    @ExceptionHandler(CaptureException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> invalid(CaptureException e) { return Map.of("error", Map.of("code", e.code(), "message", e.getMessage())); }
}
