package com.youkia.iaa.api;

import com.youkia.iaa.service.AiConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/ai-config")
public class AiConfigController {
    private final AiConfigService service; public AiConfigController(AiConfigService service){this.service=service;}
    @GetMapping public ApiResponse<?> get(){return ApiResponse.of(service.get());}
    @PutMapping public ApiResponse<?> save(@Valid @RequestBody AiConfigRequest req){return ApiResponse.of(service.save(req));}
}
