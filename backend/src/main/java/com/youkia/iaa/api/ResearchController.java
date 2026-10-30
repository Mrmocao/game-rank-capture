package com.youkia.iaa.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.youkia.iaa.service.ResearchService;
import org.springframework.web.bind.annotation.*;
import java.time.*;
@RestController @RequestMapping("/api/v1/research")
public class ResearchController {
    private final ResearchService service;public ResearchController(ResearchService s){service=s;}
    @GetMapping("/profile") public ApiResponse<?> profile(){return ApiResponse.of(service.profile());}
    @PostMapping("/profile") public ApiResponse<?> publish(@RequestBody JsonNode b){return ApiResponse.of(service.publish(b));}
    @GetMapping("/catalog") public ApiResponse<?> catalog(){return ApiResponse.of(service.catalog());}
    @GetMapping("/packets") public ApiResponse<?> packets(@RequestParam(required=false) Long versionId,@RequestParam LocalDate date){return ApiResponse.of(service.packets(versionId,date));}
    @PostMapping("/versions/{id}/packets") public ApiResponse<?> packet(@PathVariable long id,@RequestBody JsonNode b){return ApiResponse.of(service.importPacket(id,b));}
    @GetMapping("/opinions") public ApiResponse<?> opinions(@RequestParam LocalDate date){return ApiResponse.of(service.opinions(date));}
    @GetMapping("/constraints") public ApiResponse<?> constraints(){return ApiResponse.of(service.constraints());}
    @PostMapping("/constraints/{id}/resolve") public ApiResponse<?> resolve(@PathVariable long id,@RequestBody JsonNode b){return ApiResponse.of(service.resolve(id,b));}
    @PostMapping("/simulations") public ApiResponse<?> simulate(@RequestBody JsonNode b){return ApiResponse.of(service.simulation(b));}
    @GetMapping("/selections/{id}/outcomes") public ApiResponse<?> outcomes(@PathVariable long id){return ApiResponse.of(service.outcomes(id));}
    @PostMapping("/selections/{id}/outcomes") public ApiResponse<?> outcome(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode b){return ApiResponse.of(service.outcome(id,key,b));}
}
