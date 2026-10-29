package com.sicost.costallocation;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.CostActions;
import com.sicost.common.security.PermissionService;
import com.sicost.common.security.Sensitive;
import com.sicost.common.validation.CostValues;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.sicost.costallocation.CostAllocationModels.*;

@RestController
public class CostAllocationController {
    private final CostAllocationService allocations;
    private final PermissionService permissions;

    public CostAllocationController(CostAllocationService allocations, PermissionService permissions) {
        this.allocations = allocations;
        this.permissions = permissions;
    }

    @Sensitive
    @GetMapping("/api/costs/allocation")
    public ApiResponse<Allocation> getCostAllocation(@RequestParam(required = false) String month,
            @RequestParam(required = false) String projectId) {
        permissions.can(CostActions.key("getCostAllocation"));
        return ApiResponse.ok(allocations.calculate(month, CostValues.optionalId(projectId, "projectId")));
    }
}
