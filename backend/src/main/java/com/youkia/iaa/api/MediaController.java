package com.youkia.iaa.api;
import com.youkia.iaa.service.IconCache;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Files;
@RestController @RequestMapping("/api/v1/media")
public class MediaController {
 @GetMapping("/{key}.png") public ResponseEntity<?> image(@PathVariable String key){
  if(!key.matches("[a-f0-9]{64}"))return ResponseEntity.notFound().build();
  var file=IconCache.directory().resolve(key+".png");if(!Files.isRegularFile(file))return ResponseEntity.notFound().build();
  return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1))).body(new FileSystemResource(file));
 }
}
