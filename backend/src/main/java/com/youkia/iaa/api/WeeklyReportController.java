package com.youkia.iaa.api;

import com.youkia.iaa.service.WeeklyReportService;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController @RequestMapping("/api/v1")
public class WeeklyReportController {
    private final WeeklyReportService service; public WeeklyReportController(WeeklyReportService service){this.service=service;}
    @GetMapping("/reports/latest") public ApiResponse<?> latest(@RequestParam(defaultValue="false") boolean pageView){var report=service.latest();return ApiResponse.of(pageView?com.youkia.iaa.service.ReportPresentation.page(report):report);}
    @GetMapping("/reports") public ApiResponse<?> history(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return ApiResponse.of(service.history(page,pageSize));}
    @GetMapping("/reports/skill") public ApiResponse<?> skill(){return ApiResponse.of(service.skill());}
    @GetMapping("/reports/{id}") public ApiResponse<?> get(@PathVariable long id,@RequestParam(defaultValue="false") boolean pageView){var report=service.report(id);return ApiResponse.of(pageView?com.youkia.iaa.service.ReportPresentation.page(report):report);}
    @PostMapping("/admin/reports/generate") public ApiResponse<?> generate(@RequestParam @NotNull LocalDate periodStart,@RequestParam @NotNull LocalDate periodEnd,@RequestParam(required=false) java.time.LocalDateTime availableAt,@RequestParam(defaultValue="false") boolean pageView){var report=service.generate(periodStart,periodEnd,availableAt);return ApiResponse.of(pageView?com.youkia.iaa.service.ReportPresentation.page(report):report);}
}
