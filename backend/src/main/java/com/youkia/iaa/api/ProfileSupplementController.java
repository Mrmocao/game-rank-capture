package com.youkia.iaa.api;
import com.youkia.iaa.collection.capture.ProfileSupplementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/capture-workspace/supplements")
public final class ProfileSupplementController {
    private final ProfileSupplementService service;private final String tenant;
    public ProfileSupplementController(ProfileSupplementService service,@Value("${PROFILE_TENANT_ID:default}") String tenant){this.service=service;this.tenant=tenant;}
    @GetMapping public ApiResponse<?> overview(){return ApiResponse.of(service.overview(tenant));}
    @GetMapping("/observations/{id}") public ApiResponse<?> observation(@PathVariable long id){return ApiResponse.of(service.observation(tenant,id));}
    @PostMapping("/enqueue") public ApiResponse<?> enqueue(){return ApiResponse.of(service.enqueue(tenant));}
}
