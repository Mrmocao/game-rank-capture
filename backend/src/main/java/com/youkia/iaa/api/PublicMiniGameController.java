package com.youkia.iaa.api;
import com.youkia.iaa.collection.PublicMiniGameService;
import com.youkia.iaa.service.ProductQueryService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class PublicMiniGameController {
 private final PublicMiniGameService service;private final ProductQueryService products;
 public PublicMiniGameController(PublicMiniGameService service,ProductQueryService products){this.service=service;this.products=products;}
 @PostMapping(value="/admin/sources/gravity/collect-material",consumes="text/html") public ApiResponse<?> ingest(@RequestParam String platform,@RequestBody String raw){return ApiResponse.of(service.ingest(platform,raw));}
 @GetMapping("/public-rankings") public ApiResponse<?> query(@RequestParam String platform,@RequestParam String date){return ApiResponse.of(service.query(platform,products.date(date)));}
}
