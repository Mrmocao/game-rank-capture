package com.youkia.iaa.api;

import com.youkia.iaa.service.CollectionCatalogService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/collection")
public class CollectionCatalogController {
    private final CollectionCatalogService service;

    public CollectionCatalogController(CollectionCatalogService service) { this.service = service; }

    @GetMapping("/sources")
    public ApiResponse<?> sources(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String platform) {
        return ApiResponse.of(service.catalog(q, state, platform));
    }

    @GetMapping("/sources/{id}")
    public ApiResponse<?> source(@PathVariable String id) { return ApiResponse.of(service.source(id)); }

    @GetMapping("/portal-fields")
    public ApiResponse<?> portalFields() { return ApiResponse.of(service.portalFields()); }

    @GetMapping("/runs")
    public ApiResponse<?> runs(@RequestParam(required = false) LocalDate date,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String sourceId) {
        return ApiResponse.of(service.runs(date, status, sourceId));
    }
}
