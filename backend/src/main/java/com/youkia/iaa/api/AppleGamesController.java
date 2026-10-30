package com.youkia.iaa.api;

import com.youkia.iaa.collection.AppleGamesService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/admin/sources/apple-games")
public class AppleGamesController {
    private final AppleGamesService service;
    public AppleGamesController(AppleGamesService service) {this.service=service;}
    @PostMapping(value="/collect-material",consumes="application/json")
    public ApiResponse<?> collect(@RequestParam(defaultValue="cn") String country,
            @RequestParam(defaultValue="100") int limit, @RequestParam(required=false) LocalDate businessDate,
            @RequestBody String raw) {
        return ApiResponse.of(service.ingest(country,limit,businessDate,raw));
    }
}
