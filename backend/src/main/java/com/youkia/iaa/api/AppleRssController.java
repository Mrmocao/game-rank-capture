package com.youkia.iaa.api;

import com.youkia.iaa.collection.AppleRssCollectionService;
import com.youkia.iaa.collection.AppleRssPreviewService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController @RequestMapping("/api/v1/admin/sources/apple-rss")
public class AppleRssController {
    private final AppleRssPreviewService preview; private final AppleRssCollectionService collection;
    public AppleRssController(AppleRssPreviewService preview,AppleRssCollectionService collection){this.preview=preview;this.collection=collection;}
    @GetMapping("/preview") public ApiResponse<?> preview(@RequestParam(defaultValue="cn") String country,@RequestParam(defaultValue="10") Integer limit){return ApiResponse.of(preview.preview(country,limit));}
    @PostMapping("/collect") public ApiResponse<?> collect(@RequestParam(defaultValue="cn") String country,@RequestParam(required=false) String businessDate,@RequestParam(defaultValue="100") Integer limit){LocalDate date=businessDate==null||businessDate.isBlank()?LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")):LocalDate.parse(businessDate);return ApiResponse.of(collection.collect(country,date,limit));}
    @PostMapping(value="/collect-material", consumes="application/json") public ApiResponse<?> collectMaterial(@RequestParam(defaultValue="cn") String country,@RequestParam(required=false) String businessDate,@RequestParam(defaultValue="100") Integer limit,@RequestBody String raw){LocalDate date=businessDate==null||businessDate.isBlank()?LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")):LocalDate.parse(businessDate);return ApiResponse.of(collection.collectMaterial(country,date,limit,raw));}
}
