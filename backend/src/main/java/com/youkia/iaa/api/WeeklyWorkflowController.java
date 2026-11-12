package com.youkia.iaa.api;

import com.youkia.iaa.service.WeeklyWorkflowService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class WeeklyWorkflowController {
    private final WeeklyWorkflowService workflow;
    public WeeklyWorkflowController(WeeklyWorkflowService workflow) { this.workflow = workflow; }

    @GetMapping("/reports/{id}/delivery")
    public ApiResponse<?> delivery(@PathVariable long id) { return ApiResponse.of(workflow.delivery(id)); }

    @PostMapping("/admin/reports/{id}/delivery")
    public ApiResponse<?> createDelivery(@PathVariable long id) { return ApiResponse.of(workflow.ensureDelivery(id)); }

    @PostMapping("/admin/reports/{id}/delivery/ack")
    public ApiResponse<?> acknowledge(@PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.of(workflow.acknowledge(id, body == null ? null : body.get("actor"), body == null ? null : body.get("note")));
    }

    @GetMapping("/reports/{id}/review")
    public ApiResponse<?> review(@PathVariable long id) { return ApiResponse.of(workflow.review(id)); }

    @GetMapping("/reports/review/latest")
    public ApiResponse<?> latestReview() { return ApiResponse.of(workflow.latestReview()); }

    @PostMapping("/admin/reports/{id}/review")
    public ApiResponse<?> generateReview(@PathVariable long id) { return ApiResponse.of(workflow.generateReview(id)); }
}
