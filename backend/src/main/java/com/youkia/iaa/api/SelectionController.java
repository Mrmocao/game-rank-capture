package com.youkia.iaa.api;

import com.youkia.iaa.service.SelectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/v1")
public class SelectionController {
    private final SelectionService service; public SelectionController(SelectionService service){this.service=service;}
    @GetMapping("/selections") public ApiResponse<?> list(@RequestParam(required=false) Long productId,@RequestParam(required=false) String owner,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return ApiResponse.of(service.list(productId,owner,status,page,Math.min(Math.max(pageSize,1),100)));}
    @PostMapping("/products/{id}/selections") public ApiResponse<?> create(@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody SelectionRequest req){return ApiResponse.of(service.create(id,req,key));}
    @PatchMapping("/selections/{id}") public ApiResponse<?> update(@PathVariable long id,@Valid @RequestBody SelectionPatch req){return ApiResponse.of(service.update(id,req));}
}
