package com.youkia.iaa.api;
import com.youkia.iaa.collection.InsightCollectionService;
import org.springframework.web.bind.annotation.*;
import java.time.*;

@RestController @RequestMapping("/api/v1/admin/insight-collection")
public class InsightCollectionController {
    private final InsightCollectionService service;
    public InsightCollectionController(InsightCollectionService service){this.service=service;}
    @GetMapping public ApiResponse<?> status(@RequestParam(required=false) LocalDate date){return ApiResponse.of(service.status(date==null?LocalDate.now(ZoneId.of("Asia/Shanghai")):date));}
    @PostMapping("/run") public ApiResponse<?> collect(){return ApiResponse.of(service.collect());}
}
