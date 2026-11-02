package com.sicost.apiops;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.apiops.ClosingQueryModels.*;

@RestController
public class ClosingQueryController {
    private final ClosingQueryService service;
    private final PermissionService permissions;
    public ClosingQueryController(ClosingQueryService service, PermissionService permissions) {
        this.service = service;
        this.permissions = permissions;
    }
    @Sensitive
    @GetMapping(value = "/api/closing", produces = "application/json")
    public ApiResponse<Closing> getClosing(@RequestParam(required = false) String month) {
        permissions.can(ClosingActions.READ);
        return ApiResponse.ok(service.getClosing(month));
    }
}
