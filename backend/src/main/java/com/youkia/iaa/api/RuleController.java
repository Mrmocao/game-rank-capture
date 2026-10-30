package com.youkia.iaa.api;

import com.youkia.iaa.service.RuleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/rules")
public class RuleController {
    private final RuleService service; public RuleController(RuleService service){this.service=service;}
    @GetMapping("/current") public ApiResponse<?> current(){return ApiResponse.of(service.current());}
    @PostMapping("/publish") public ApiResponse<?> publish(@Valid @RequestBody RulePublishRequest req){return ApiResponse.of(service.publish(req));}
}
