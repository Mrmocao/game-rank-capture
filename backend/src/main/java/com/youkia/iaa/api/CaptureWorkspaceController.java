package com.youkia.iaa.api;

import com.youkia.capture.CaptureException;
import com.youkia.iaa.collection.capture.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/capture-workspace")
public class CaptureWorkspaceController {
    private final CaptureWorkspaceStore store;private final CaptureWorkspaceService workspace;private final CaptureRepairService repair;private final String tenant;
    public CaptureWorkspaceController(CaptureWorkspaceStore store,CaptureWorkspaceService workspace,CaptureRepairService repair,@Value("${PROFILE_TENANT_ID:default}")String tenant){this.store=store;this.workspace=workspace;this.repair=repair;this.tenant=tenant;}
    @GetMapping public ApiResponse<?> overview(){var out=new LinkedHashMap<>(workspace.overview(tenant));out.put("ai",repair.aiStatus());return ApiResponse.of(out);}
    @PostMapping("/targets") public ApiResponse<?> add(@RequestBody CaptureWorkspaceStore.TargetEdit edit){store.initialize(tenant);return ApiResponse.of(store.add(tenant,edit));}
    @PutMapping("/targets/{key}") public ApiResponse<?> edit(@PathVariable String key,@RequestBody CaptureWorkspaceStore.TargetEdit edit){store.initialize(tenant);return ApiResponse.of(store.update(tenant,key,edit));}
    @PutMapping("/schedule") public ApiResponse<?> schedule(@RequestBody CaptureWorkspaceStore.ScheduleEdit edit){store.initialize(tenant);return ApiResponse.of(store.saveSchedule(tenant,edit));}
    @PostMapping("/run") public ApiResponse<?> run(@RequestParam(required=false)String target){return ApiResponse.of(workspace.run(tenant,target));}
    @GetMapping("/repairs") public ApiResponse<?> repairs(){return ApiResponse.of(repair.list(tenant));}
    public record RepairRequest(String runId){}
    @PostMapping("/repairs") public ApiResponse<?> createRepair(@RequestBody RepairRequest request){return ApiResponse.of(repair.create(tenant,request.runId()));}
    @GetMapping("/repairs/{id}") public ApiResponse<?> job(@PathVariable String id){return ApiResponse.of(repair.get(tenant,id));}
    @PostMapping("/repairs/{id}/diagnose") public ApiResponse<?> diagnose(@PathVariable String id){return ApiResponse.of(repair.diagnose(tenant,id));}
    @PostMapping("/repairs/{id}/retry") public ApiResponse<?> retry(@PathVariable String id){return ApiResponse.of(repair.retry(tenant,id));}
    @ExceptionHandler(CaptureException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String,Object> invalid(CaptureException error){return Map.of("error",Map.of("code",error.code(),"message",error.getMessage()));}
}
