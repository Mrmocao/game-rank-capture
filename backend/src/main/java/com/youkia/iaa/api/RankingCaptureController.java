package com.youkia.iaa.api;

import com.youkia.capture.*;
import com.youkia.iaa.collection.capture.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/ranking-capture")
public class RankingCaptureController {
    private final RankingCaptureRunner runner;
    private final RankingCaptureStore store;
    private final String tenant;
    public RankingCaptureController(RankingCaptureRunner runner, RankingCaptureStore store,
            @Value("${PROFILE_TENANT_ID:default}") String tenant) { this.runner = runner; this.store = store; this.tenant = tenant; }
    public record Request(List<CaptureRequest> requests) {}
    @PostMapping("/run") public ApiResponse<?> run(@RequestBody(required = false) Request request) {
        return ApiResponse.of(runner.run(tenant, request == null || request.requests() == null ? CaptureSources.daily(100) : request.requests()));
    }
    @GetMapping("/sources") public ApiResponse<?> sources() { return ApiResponse.of(runner.sources()); }
    @GetMapping("/runs") public ApiResponse<?> runs(@RequestParam(required = false) String batchId) { return ApiResponse.of(store.runs(tenant, batchId)); }
    @GetMapping("/observations") public ApiResponse<?> observations(@RequestParam(required = false) String platform,
            @RequestParam(required = false) LocalDate date, @RequestParam(required = false) String source, @RequestParam(required = false) String chart) {
        return ApiResponse.of(store.observations(tenant, platform, date, source, chart));
    }
    @GetMapping("/observations/{id}") public ApiResponse<?> observation(@PathVariable long id) { return ApiResponse.of(store.observation(tenant, id)); }
    @ExceptionHandler(CaptureException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> invalid(CaptureException error) { return Map.of("error", Map.of("code", error.code(), "message", error.getMessage())); }
}
