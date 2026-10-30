package com.youkia.iaa.api;

import com.youkia.iaa.service.ProductQueryService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class ProductController {
    private final ProductQueryService service; public ProductController(ProductQueryService service){this.service=service;}
    @GetMapping("/meta/coverage") public ApiResponse<?> coverage(@RequestParam(required=false) String date){return ApiResponse.of(service.coverage(date));}
    @GetMapping("/products/stats") public ApiResponse<?> stats(@RequestParam(required=false) String date){return ApiResponse.of(service.statistics(date));}
    @GetMapping("/products") public ApiResponse<?> products(@RequestParam(required=false) String date,@RequestParam(required=false) String q,@RequestParam(required=false) String platform,@RequestParam(required=false) String region,@RequestParam(required=false) Long chartId,@RequestParam(required=false) String poolState,@RequestParam(required=false) String selectionState,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return ApiResponse.of(service.list(date,q,platform,region,chartId,poolState,selectionState,page,pageSize));}
    @GetMapping("/products/{id}") public ApiResponse<?> product(@PathVariable long id,@RequestParam(required=false) String date){return ApiResponse.of(service.product(id,date));}
    @GetMapping("/products/{id}/versions") public ApiResponse<?> versions(@PathVariable long id,@RequestParam(required=false) String date){Map<String,Object> out=new LinkedHashMap<>();out.put("items",service.versions(id,date));out.put("date",date);return ApiResponse.of(out);}
    @GetMapping("/rankings") public ApiResponse<?> rankings(@RequestParam long chartId,@RequestParam String date){return ApiResponse.of(Map.of("items",service.rankings(chartId,date),"chartId",chartId,"date",date));}
    @GetMapping("/exports/products") public ResponseEntity<String> export(@RequestParam(required=false) String date,@RequestParam(required=false) String q){Map<String,Object> data=service.list(date,q,null,null,null,null,null,1,100);StringBuilder csv=new StringBuilder("date,product_id,product_name,pool_state,platform,region,chart,rank,data_state,monetization\n");for(Object obj:(List<?>)data.get("items")){Map<?,?> item=(Map<?,?>)obj;for(Object rr:(List<?>)item.get("rankings")){Map<?,?> r=(Map<?,?>)rr;csv.append(csv(data.get("date"))).append(',').append(csv(item.get("id"))).append(',').append(csv(item.get("name"))).append(',').append(csv(item.get("poolState"))).append(',').append(csv(r.get("platform"))).append(',').append(csv(r.get("region"))).append(',').append(csv(r.get("chartName"))).append(',').append(csv(r.get("rank"))).append(',').append(csv(r.get("dataState"))).append(',').append(csv(r.get("monetization"))).append('\n');} }return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=products.csv").body(csv.toString());}
    private String csv(Object x){String s=x==null?"":String.valueOf(x);if(s.startsWith("=")||s.startsWith("+")||s.startsWith("-")||s.startsWith("@"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
}
