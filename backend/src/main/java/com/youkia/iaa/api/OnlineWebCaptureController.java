package com.youkia.iaa.api;

import com.youkia.capture.CaptureException;
import com.youkia.iaa.collection.capture.OnlineWebCaptureService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/capture-workspace/web")
public class OnlineWebCaptureController {
    private final OnlineWebCaptureService web;private final String tenant;
    public OnlineWebCaptureController(OnlineWebCaptureService web,@Value("${PROFILE_TENANT_ID:default}")String tenant){this.web=web;this.tenant=tenant;}
    @GetMapping("/provider")public ApiResponse<?> provider(){return ApiResponse.of(web.provider(tenant));}
    @PutMapping("/provider")public ApiResponse<?> provider(@RequestBody OnlineWebCaptureService.ProviderEdit edit){return ApiResponse.of(web.saveProvider(tenant,edit));}
    @GetMapping("/targets/{key}/rules")public ApiResponse<?> rules(@PathVariable String key){return ApiResponse.of(web.rules(tenant,key));}
    @PutMapping("/targets/{key}/rules")public ApiResponse<?> save(@PathVariable String key,@RequestBody OnlineWebCaptureService.RuleEdit edit){return ApiResponse.of(web.saveRule(tenant,key,edit));}
    public record VersionRequest(int version){}
    public record PublishRequest(int version,int revision,String previewId){}
    @PostMapping("/targets/{key}/preview")public ApiResponse<?> preview(@PathVariable String key,@RequestBody VersionRequest request){return ApiResponse.of(web.execute(tenant,key,request.version()));}
    @PostMapping("/targets/{key}/publish")public ApiResponse<?> publish(@PathVariable String key,@RequestBody PublishRequest request){return ApiResponse.of(web.publish(tenant,key,request.version(),request.revision(),request.previewId()));}
    @PostMapping("/targets/{key}/run")public ApiResponse<?> run(@PathVariable String key){return ApiResponse.of(web.execute(tenant,key,null));}
    @GetMapping("/runs")public ApiResponse<?> runs(@RequestParam(required=false)String target){return ApiResponse.of(web.runs(tenant,target));}
    @GetMapping("/runs/{id}")public ApiResponse<?> detail(@PathVariable String id){return ApiResponse.of(web.detail(tenant,id));}
    @ExceptionHandler(CaptureException.class)@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String,Object> invalid(CaptureException e){return Map.of("error",Map.of("code",e.code(),"message",e.getMessage()));}
}
