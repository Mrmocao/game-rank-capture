package com.youkia.iaa.api;
import com.youkia.iaa.collection.AppBrainService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/sources/appbrain")
public class AppBrainController {
    private final AppBrainService service;
    public AppBrainController(AppBrainService service){this.service=service;}
    @PostMapping(value="/collect-material",consumes="text/html")
    public ApiResponse<?> collect(@RequestParam String country,@RequestBody String raw){return ApiResponse.of(service.ingest(country,raw));}
}
