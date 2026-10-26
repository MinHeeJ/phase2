package com.sicost.projectsops;

import com.sicost.common.api.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.projectsops.ProjectLifecycleModels.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectLifecycleController {
    private final ProjectLifecycleService service;
    public ProjectLifecycleController(ProjectLifecycleService service){this.service=service;}
    private void allowed(Map<String,String> query,Set<String> keys){query.keySet().forEach(k->{if(!keys.contains(k))throw ApiException.badRequest("조회 조건을 확인하세요.",k);});}
    @GetMapping("/export")
    public ApiResponse<DownloadArtifact> getProjectExport(@RequestParam Map<String,String> query){allowed(query,Set.of("year","type","status","part","q","sort"));return ApiResponse.ok(service.export(query));}
    @GetMapping("/options")
    public ApiResponse<List<ProjectOption>> listProjectOptions(@RequestParam Map<String,String> query){allowed(query,Set.of("q"));return ApiResponse.ok(service.options(query.get("q")));}
    @GetMapping("/{id}/assignments")
    public ApiResponse<List<ProjectAssignment>> listProjectAssignments(@PathVariable long id,@RequestParam Map<String,String> query){allowed(query,Set.of());return ApiResponse.ok(service.assignments(id));}
    @GetMapping("/{id}/history")
    public ApiResponse<List<ProjectHistoryEntry>> listProjectHistory(@PathVariable long id,@RequestParam Map<String,String> query){allowed(query,Set.of());return ApiResponse.ok(service.history(id));}
    @PostMapping("/{id}/disable")
    public ApiResponse<Map<String,Object>> updateProjectDisable(@PathVariable long id){return ApiResponse.ok(service.disable(id));}
    @PostMapping("/{id}/enable")
    public ApiResponse<Map<String,Object>> updateProjectEnable(@PathVariable long id){return ApiResponse.ok(service.enable(id));}
}
