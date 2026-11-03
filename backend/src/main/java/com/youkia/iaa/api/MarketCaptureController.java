package com.youkia.iaa.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.youkia.iaa.service.MarketCaptureService;
import org.springframework.web.bind.annotation.*;
import java.time.*;
@RestController @RequestMapping("/api/v1/market")
public class MarketCaptureController {
    private final MarketCaptureService service;
    public MarketCaptureController(MarketCaptureService service){this.service=service;}
    private LocalDate day(LocalDate value){if(value!=null)return value;var latest=service.latest();return latest==null?LocalDate.now(ZoneId.of("Asia/Shanghai")):latest;}
    @GetMapping("/coverage") public ApiResponse<?> coverage(@RequestParam(required=false)LocalDate date){return ApiResponse.of(service.catalog(day(date)));}
    @GetMapping("/remote-capture-plan") public ApiResponse<?> remotePlan(){return ApiResponse.of(service.remotePlan());}
    @GetMapping("/rankings") public ApiResponse<?> rankings(@RequestParam LocalDate date,@RequestParam String chartId){return ApiResponse.of(service.ranking(date,chartId));}
    @GetMapping("/candidates") public ApiResponse<?> candidates(@RequestParam LocalDate date,@RequestParam(required=false)String platform,@RequestParam(required=false)String q){return ApiResponse.of(service.candidates(date,platform,q));}
    @GetMapping("/selections") public ApiResponse<?> selections(){return ApiResponse.of(service.selections());}
    @PostMapping("/selections") public ApiResponse<?> select(@RequestBody JsonNode body,@RequestHeader("Idempotency-Key")String key){return ApiResponse.of(service.select(body,key));}
}
