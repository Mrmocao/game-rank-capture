package com.youkia.iaa.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.youkia.iaa.service.SourceIdentityService;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/market/identity")
public class SourceIdentityController {
    private final SourceIdentityService service;
    public SourceIdentityController(SourceIdentityService service){this.service=service;}
    @GetMapping("/products") public ApiResponse<?> products(@RequestParam(defaultValue="")String q){return ApiResponse.of(service.products(q));}
    @GetMapping("/history") public ApiResponse<?> history(@RequestParam long observationId,@RequestParam int rank){return ApiResponse.of(service.history(observationId,rank));}
    @PostMapping("/reviews") public ApiResponse<?> review(@RequestBody JsonNode body,@RequestHeader("Idempotency-Key")String key){return ApiResponse.of(service.review(body,key));}
}
