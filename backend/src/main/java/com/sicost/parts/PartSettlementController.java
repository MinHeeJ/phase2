package com.sicost.parts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.*;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.parts.PartSettlementModels.*;

@RestController
public class PartSettlementController {
    private final PartSettlementService service;
    private final PartSettlementExportService exports;
    private final ObjectMapper json;
    private final PermissionService permissions;
    public PartSettlementController(PartSettlementService service, PartSettlementExportService exports,
            ObjectMapper json, PermissionService permissions) {
        this.service = service;
        this.exports = exports;
        this.json = json;
        this.permissions = permissions;
    }
    @Sensitive
    @GetMapping(value = "/api/settlements/parts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SettlementList> listPartSettlements(@RequestParam(required = false) String month) {
        permissions.can(CostActions.SETTLEMENT_READ);
        return ApiResponse.ok(service.list(month));
    }
    @Sensitive
    @GetMapping(value = "/api/settlements/parts",
        produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> downloadPartSettlements(@RequestParam(required = false) String month) {
        permissions.can(CostActions.SETTLEMENT_DOWNLOAD);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("파트정산.xlsx", StandardCharsets.UTF_8).build().toString())
            .body(exports.download(month));
    }
    @Sensitive
    @GetMapping("/api/settlements/parts/{partId}")
    public ApiResponse<Detail> getPartSettlement(@PathVariable String partId, @RequestParam(required = false) String month,
            @RequestParam(required = false) String view) {
        permissions.can(CostActions.SETTLEMENT_READ);
        return ApiResponse.ok(service.detail(service.scope(PartSettlementInput.id(partId), month), view));
    }
    @Sensitive
    @PutMapping("/api/settlements/parts/{partId}/card")
    public ApiResponse<Detail> updatePartSettlementCard(@PathVariable String partId, @RequestParam(required = false) String month,
            @RequestBody(required = false) String body) {
        permissions.can(CostActions.SETTLEMENT_UPDATE);
        return ApiResponse.ok(service.card(service.scope(PartSettlementInput.id(partId), month),
            PartSettlementInput.card(PartSettlementInput.body(json, body))));
    }
    @Sensitive
    @PostMapping("/api/settlements/parts/{partId}/confirm")
    public ApiResponse<Detail> confirmPartSettlement(@PathVariable String partId, @RequestParam(required = false) String month) {
        permissions.can(CostActions.SETTLEMENT_CONFIRM);
        return ApiResponse.ok(service.confirm(service.scope(PartSettlementInput.id(partId), month)));
    }
    @Sensitive
    @PostMapping("/api/settlements/parts/{partId}/cancel")
    public ApiResponse<Detail> cancelPartSettlement(@PathVariable String partId, @RequestParam(required = false) String month,
            @RequestBody(required = false) String body) {
        permissions.can(CostActions.SETTLEMENT_CANCEL);
        return ApiResponse.ok(service.cancel(service.scope(PartSettlementInput.id(partId), month),
            PartSettlementInput.cancel(PartSettlementInput.body(json, body))));
    }
}
