package com.sicost.common.costs;

import com.sicost.apiops.ProjectCostService;
import com.sicost.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import static com.sicost.common.costs.CostAllocationModels.*;

@RestController
public class CostAllocationController {
    private final ProjectCostService service;
    public CostAllocationController(ProjectCostService service) { this.service = service; }
    @GetMapping("/api/costs/allocation")
    public ApiResponse<Result> getCostAllocation(@RequestParam(required = false) String month,
            @RequestParam(required = false) String projectId) {
        return ApiResponse.ok(service.allocation(CostAllocationService.month(month),
            CostAllocationService.projectId(projectId)));
    }
}
