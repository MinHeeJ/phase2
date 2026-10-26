package com.sicost.projectsops;

import com.sicost.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import static com.sicost.projectsops.ProjectProgressModels.*;

@RestController
public class ProjectProgressController {
    private final ProjectProgressService service;
    public ProjectProgressController(ProjectProgressService service) { this.service=service; }
    @GetMapping("/api/projects/{id}/progress")
    public ApiResponse<Progress> getProjectProgress(@PathVariable long id) { return ApiResponse.ok(service.get(id)); }
    @GetMapping("/api/projects/{id}/issues")
    public ApiResponse<ProjectIssues> listProjectIssues(@PathVariable long id) { return ApiResponse.ok(service.issues(id)); }
}
