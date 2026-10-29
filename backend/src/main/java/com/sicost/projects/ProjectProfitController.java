package com.sicost.projects;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.projects.ProjectProfitModels.*;

@RestController
public class ProjectProfitController {
    private final ProjectProfitService service;
    public ProjectProfitController(ProjectProfitService service) { this.service = service; }
    @GetMapping("/api/costs/projects")
    public ApiResponse<Page> listCostProjects(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.list(query));
    }
    @GetMapping("/api/costs/projects/{id}")
    public ApiResponse<Detail> getCostProject(@PathVariable String id,
            @RequestParam(required=false) String year) {
        return ApiResponse.ok(service.get(id,year));
    }
    @GetMapping("/api/costs/projects/{id}/causes")
    public ApiResponse<Causes> getCostProjectCauses(@PathVariable String id,
            @RequestParam(required=false) String year) {
        return ApiResponse.ok(service.causes(id,year));
    }
    @PostMapping("/api/costs/projects/{id}/simulate")
    public ApiResponse<Object> simulateCostProject(@PathVariable String id,
            @RequestBody(required=false) String body) {
        return ApiResponse.ok(service.simulate(id));
    }
}
