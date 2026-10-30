package com.youkia.iaa.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.youkia.iaa.service.GameProfileService;
import org.springframework.web.bind.annotation.*;
import java.time.*;

@RestController @RequestMapping("/api/v1")
public class GameProfileController {
    private final GameProfileService profiles;
    public GameProfileController(GameProfileService profiles){this.profiles=profiles;}
    @GetMapping("/profile/definitions") public ApiResponse<?> definitions(){return ApiResponse.of(profiles.definitions());}
    @GetMapping("/profile/products") public ApiResponse<?> products(@RequestParam LocalDate date,@RequestParam(required=false) String q,@RequestParam(required=false) String platform,@RequestParam(required=false) String region,@RequestParam(required=false) String qualification,@RequestParam(required=false) String gap,@RequestParam(required=false) Long chartId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return ApiResponse.of(profiles.products(date,q,platform,region,qualification,gap,chartId,page,pageSize));}
    @GetMapping("/products/{id}/profile") public ApiResponse<?> profile(@PathVariable long id,@RequestParam(required=false) Long listingId,@RequestParam(required=false) Long versionId,@RequestParam(required=false) String market,@RequestParam LocalDate asOf,@RequestParam(defaultValue="Asia/Shanghai") String timezone){return ApiResponse.of(profiles.profile(id,listingId,versionId,market,asOf,timezone));}
    @GetMapping("/products/{id}/profile-export") public ApiResponse<?> export(@PathVariable long id,@RequestParam Long listingId,@RequestParam LocalDate asOf,@RequestParam(defaultValue="Asia/Shanghai") String timezone){return ApiResponse.of(profiles.export(id,listingId,asOf,timezone));}
    @GetMapping("/profile/listings/{id}/fields/{key}/history") public ApiResponse<?> history(@PathVariable long id,@PathVariable String key,@RequestParam LocalDate asOf,@RequestParam(defaultValue="Asia/Shanghai") String timezone){return ApiResponse.of(profiles.history(id,key,asOf,timezone));}
    @GetMapping("/profile/evidence/{id}") public ApiResponse<?> evidence(@PathVariable long id,@RequestParam LocalDate asOf,@RequestParam(defaultValue="Asia/Shanghai") String timezone){return ApiResponse.of(profiles.evidence(id,asOf,timezone));}
    @PostMapping("/profile/tasks/plan") public ApiResponse<?> plan(@RequestBody JsonNode body){return ApiResponse.of(profiles.plan(body.path("listingId").asLong(),LocalDate.parse(body.path("asOf").asText()),body.path("timezone").asText("Asia/Shanghai")));}
    @PatchMapping("/profile/tasks/{id}") public ApiResponse<?> task(@PathVariable long id,@RequestBody JsonNode body){return ApiResponse.of(profiles.updateTask(id,body));}
    @PostMapping("/profile/submissions") public ApiResponse<?> submit(@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body){return ApiResponse.of(profiles.submit(body,key));}
    @PostMapping("/profile/submissions/{id}/review") public ApiResponse<?> review(@PathVariable long id,@RequestBody JsonNode body){return ApiResponse.of(profiles.review(id,body));}
}
