package com.youkia.iaa.api;

import com.youkia.iaa.collection.capture.GameMetricService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/admin/capture-workspace/metrics/remote")
public class RemoteMetricController {
    private final GameMetricService service;private final String tenant,token;
    public RemoteMetricController(GameMetricService service,@Value("${PROFILE_TENANT_ID:default}")String tenant,@Value("${CAPTURE_REMOTE_METRIC_TOKEN:}")String token){this.service=service;this.tenant=tenant;this.token=token;}
    private void authorize(String supplied){if(token.isBlank()||supplied==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);}
    @PostMapping("/claim")public ApiResponse<?> claim(@RequestHeader(value="X-Capture-Metrics-Token",required=false)String supplied){authorize(supplied);return ApiResponse.of(service.claimRemote(tenant));}
    @PostMapping("/result")public ApiResponse<?> result(@RequestHeader(value="X-Capture-Metrics-Token",required=false)String supplied,@RequestBody GameMetricService.RemoteResult result){authorize(supplied);return ApiResponse.of(service.acceptRemote(tenant,result));}
}
