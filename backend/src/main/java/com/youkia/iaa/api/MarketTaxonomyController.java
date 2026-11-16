package com.youkia.iaa.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.youkia.iaa.service.MarketTaxonomyService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequestMapping("/api/v1/market-taxonomy")
public class MarketTaxonomyController {
    private final MarketTaxonomyService service;
    public MarketTaxonomyController(MarketTaxonomyService service){this.service=service;}
    @GetMapping public ApiResponse<?> list(@RequestParam(required=false) LocalDate date){return ApiResponse.of(service.list(date));}
    @PostMapping("/terms") public ApiResponse<?> term(@RequestBody JsonNode body){return ApiResponse.of(service.createTerm(body));}
    @GetMapping("/terms/{id}/observations") public ApiResponse<?> observations(@PathVariable long id,@RequestParam(required=false) LocalDate date){return ApiResponse.of(service.observations(id,date));}
    @PostMapping("/terms/{id}/observations") public ApiResponse<?> observation(@PathVariable long id,@RequestBody JsonNode body){return ApiResponse.of(service.observe(id,body));}
}
