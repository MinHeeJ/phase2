package com.sicost.costsops;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.CostActions;
import com.sicost.common.security.PermissionService;
import com.sicost.common.security.Sensitive;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.sicost.costsops.RenegotiationModels.*;

@RestController
public class RenegotiationController {
    private final RenegotiationService service;
    private final RenegotiationExportService exports;
    private final PermissionService permissions;

    public RenegotiationController(RenegotiationService service, RenegotiationExportService exports,
            PermissionService permissions) {
        this.service = service;
        this.exports = exports;
        this.permissions = permissions;
    }

    @Sensitive
    @GetMapping(value = "/api/costs/renegotiations", produces = "application/json")
    public ApiResponse<Result> listCostRenegotiations(@RequestParam Map<String, String> query) {
        return ApiResponse.ok(service.list(RenegotiationInput.query(query), false));
    }

    @Sensitive
    @GetMapping(value = "/api/costs/renegotiations", produces = RenegotiationExportService.XLSX)
    public ResponseEntity<byte[]> downloadCostRenegotiations(@RequestParam Map<String, String> query) {
        permissions.can(CostActions.RENEGOTIATION_DOWNLOAD);
        return exports.download(RenegotiationInput.query(query));
    }
}
