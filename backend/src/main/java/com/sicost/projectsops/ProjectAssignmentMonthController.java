package com.sicost.projectsops;

import com.sicost.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import static com.sicost.projectsops.ProjectAssignmentMonthModels.*;

@RestController
public class ProjectAssignmentMonthController {
    private final ProjectAssignmentMonthService service;
    public ProjectAssignmentMonthController(ProjectAssignmentMonthService service) { this.service = service; }

    @GetMapping("/api/projects/{id}/assignment-months")
    public ApiResponse<Result> getProjectAssignmentMonths(@PathVariable long id,
            @RequestParam(required = false) String year) {
        return ApiResponse.ok(service.getProjectAssignmentMonths(id, year));
    }
}
