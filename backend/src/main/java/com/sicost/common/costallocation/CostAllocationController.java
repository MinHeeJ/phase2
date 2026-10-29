package com.sicost.common.costallocation;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.sicost.common.costallocation.CostAllocationModels.*;

@RestController
public class CostAllocationController {
    private final CostAllocationService service;
    public CostAllocationController(CostAllocationService service) { this.service = service; }
    @GetMapping("/api/costs/allocation")
    public ApiResponse<Result> getCostAllocation(@RequestParam Map<String, String> input) {
        String month = CostAnalysisInput.month(input.get("month"));
        Long project = input.containsKey("projectId") ? CostAnalysisInput.id(input.get("projectId"), "projectId") : null;
        return ApiResponse.ok(service.get(month, project));
    }
}
