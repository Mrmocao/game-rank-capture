package com.youkia.iaa.api;

import com.youkia.capture.CaptureException;
import com.youkia.iaa.collection.capture.GameMetricService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/capture-workspace/metrics")
public class GameMetricController {
    private final GameMetricService metrics;private final String tenant;
    public GameMetricController(GameMetricService metrics,@Value("${PROFILE_TENANT_ID:default}")String tenant){this.metrics=metrics;this.tenant=tenant;}
    @GetMapping public ApiResponse<?> overview(@RequestParam(required=false)String platform,@RequestParam(required=false)String region,@RequestParam(required=false)String search,@RequestParam(defaultValue="1")int page){return ApiResponse.of(metrics.overview(tenant,platform,region,search,page));}
    @PutMapping("/config") public ApiResponse<?> configure(@RequestBody GameMetricService.ConfigEdit edit){return ApiResponse.of(metrics.saveConfig(tenant,edit));}
    @PostMapping("/enqueue") public ApiResponse<?> enqueue(@RequestBody(required=false)GameMetricService.Enqueue request){return ApiResponse.of(metrics.enqueue(tenant,request));}
    @GetMapping("/jobs/{id}")public ApiResponse<?> history(@PathVariable String id){return ApiResponse.of(metrics.history(tenant,id));}
    @PutMapping("/providers/{provider}")public ApiResponse<?> provider(@PathVariable String provider,@RequestBody GameMetricService.ProviderEdit edit){return ApiResponse.of(metrics.saveProvider(tenant,provider,edit));}
    @ExceptionHandler(CaptureException.class)@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String,Object> error(CaptureException e){return Map.of("error",Map.of("code",e.code(),"message",e.getMessage()));}
}
