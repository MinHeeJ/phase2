package com.sicost.closingops;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.adjustments.AdjustmentDeviceGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.closingops.ClosingModels.*;

@RestController
public class ClosingController {
    private final ClosingService service;
    private final ClosingHistoryService history;
    private final ClosingSnapshotService snapshots;
    private final ObjectMapper json;
    private final PermissionService permissions;
    public ClosingController(ClosingService service,ClosingHistoryService history,ClosingSnapshotService snapshots,
            ObjectMapper json,PermissionService permissions) {
        this.service=service;this.history=history;this.snapshots=snapshots;this.json=json;this.permissions=permissions;
    }
    @Sensitive @GetMapping("/api/closing/history")
    public ApiResponse<HistoryPage> listClosingHistory(@RequestParam String year,
            @RequestParam(defaultValue="0") String page,@RequestParam(defaultValue="20") String size) {
        permissions.can(ClosingActions.READ);
        return ApiResponse.ok(history.list(integer(year,"year"),integer(page,"page"),integer(size,"size")));
    }
    @Sensitive @GetMapping("/api/closing/preview")
    public ApiResponse<Preview> previewClosing(@RequestParam String month) {
        permissions.can(ClosingActions.READ);return ApiResponse.ok(snapshots.preview(month));
    }
    @Sensitive @PostMapping("/api/closing/check")
    public ApiResponse<CheckResult> runClosingCheck(@RequestParam String month,HttpServletRequest request) {
        AdjustmentDeviceGuard.requireDesktop(request);permissions.can(ClosingActions.UPDATE);
        return ApiResponse.ok(service.check(month));
    }
    @Sensitive @PostMapping("/api/closing/confirm")
    public ApiResponse<Result> confirmClosing(@RequestBody String body,HttpServletRequest request) {
        AdjustmentDeviceGuard.requireDesktop(request);permissions.can(ClosingActions.CONFIRM);
        JsonNode node=body(body,Set.of("month","ackWarnings"));
        if(!node.path("ackWarnings").isBoolean())throw invalid("ackWarnings");
        return ApiResponse.ok(service.confirm(text(node,"month"),node.get("ackWarnings").booleanValue()));
    }
    @Sensitive @PostMapping("/api/closing/reopen")
    public ApiResponse<Result> reopenClosing(@RequestBody String body,HttpServletRequest request) {
        AdjustmentDeviceGuard.requireDesktop(request);permissions.can(ClosingActions.REOPEN);
        JsonNode node=body(body,Set.of("month","reason"));
        return ApiResponse.ok(service.reopen(text(node,"month"),text(node,"reason")));
    }
    private JsonNode body(String body,Set<String> allowed) {
        try {
            JsonNode node=json.readTree(body);
            if(node==null||!node.isObject())throw invalid("body");
            node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw invalid(key);});return node;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw invalid("body");}
    }
    private String text(JsonNode node,String field) {if(!node.path(field).isTextual())throw invalid(field);return node.get(field).textValue();}
    private int integer(String value,String field) {try{return Integer.parseInt(value);}catch(NumberFormatException ex){throw invalid(field);}}
    private ApiException invalid(String field){return new ApiException(400,"VALIDATION_ERROR","입력값을 확인하세요.",field);}
}
