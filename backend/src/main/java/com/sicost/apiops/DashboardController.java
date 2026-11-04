package com.sicost.apiops;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.DashboardActions;
import com.sicost.common.security.PermissionService;
import org.springframework.web.bind.annotation.*;

@RestController
public class DashboardController {
    private final DashboardQueryService dashboard;
    private final PermissionService permissions;
    public DashboardController(DashboardQueryService dashboard,PermissionService permissions) {
        this.dashboard=dashboard;this.permissions=permissions;
    }
    @GetMapping("/api/dashboard")
    public ApiResponse<DashboardModels.DashboardResponse> getDashboard(@RequestParam(required=false) String month) {
        DashboardActions.can(permissions,"getDashboard");
        return ApiResponse.ok(dashboard.getDashboard(month));
    }
}
