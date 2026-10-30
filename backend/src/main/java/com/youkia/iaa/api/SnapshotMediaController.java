package com.youkia.iaa.api;
import com.youkia.iaa.collection.SnapshotMediaService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/sources/media")
public class SnapshotMediaController {
 private final SnapshotMediaService service;
 public SnapshotMediaController(SnapshotMediaService service){this.service=service;}
 @GetMapping("/index") public ApiResponse<?> index(){return ApiResponse.of(service.urls());}
 @PostMapping("/extract") public ApiResponse<?> extract(){return ApiResponse.of(service.extract());}
}
