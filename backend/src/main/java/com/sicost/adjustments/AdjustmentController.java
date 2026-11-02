package com.sicost.adjustments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.adjustments.AdjustmentModels.*;

@RestController
public class AdjustmentController {
    private final AdjustmentService service;
    private final ObjectMapper json;
    private final PermissionService permissions;
    public AdjustmentController(AdjustmentService service, ObjectMapper json, PermissionService permissions) {
        this.service=service; this.json=json; this.permissions=permissions;
    }
    @Sensitive
    @GetMapping("/api/closing/adjustments")
    public ApiResponse<Page> listClosingAdjustments(@RequestParam Map<String,String> query) {
        permissions.can(ClosingActions.READ);
        return ApiResponse.ok(service.list(AdjustmentInput.query(query)));
    }
    @Sensitive
    @PostMapping("/api/closing/adjustments")
    public ApiResponse<Row> createClosingAdjustment(@RequestBody(required=false) String body, HttpServletRequest request) {
        AdjustmentDeviceGuard.requireDesktop(request);
        permissions.can(ClosingActions.UPDATE);
        return ApiResponse.ok(service.create(AdjustmentInput.create(json,body)));
    }
    @Sensitive
    @PostMapping("/api/closing/adjustments/{id}/cancel")
    public ApiResponse<Row> cancelClosingAdjustment(@PathVariable String id, @RequestBody(required=false) String body,
            HttpServletRequest request) {
        AdjustmentDeviceGuard.requireDesktop(request);
        permissions.can(ClosingActions.UPDATE);
        return ApiResponse.ok(service.cancel(AdjustmentInput.id(id),AdjustmentInput.cancel(json,body)));
    }
}
