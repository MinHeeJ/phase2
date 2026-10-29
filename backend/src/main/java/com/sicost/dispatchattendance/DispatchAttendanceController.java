package com.sicost.dispatchattendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.BillingValues;
import org.springframework.web.bind.annotation.*;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

@RestController
public class DispatchAttendanceController {
    private final DispatchAttendanceService service;
    private final ObjectReader reader;
    public DispatchAttendanceController(DispatchAttendanceService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/dispatch-attendance")
    public ApiResponse<AttendanceResult> getDispatchAttendance(
            @RequestParam(required = false) String month, @RequestParam(required = false) String projectId) {
        return ApiResponse.ok(service.get(DispatchAttendanceInput.scope(month, projectId)));
    }
    @PutMapping("/api/dispatch-attendance")
    public ApiResponse<AttendanceResult> saveDispatchAttendance(
            @RequestParam(required = false) String month, @RequestParam(required = false) String projectId,
            @RequestBody(required = false) String body) {
        var scope = DispatchAttendanceInput.scope(month, projectId);
        if (body == null) throw BillingValues.invalid("items");
        try { return ApiResponse.ok(service.save(scope, DispatchAttendanceInput.parse(reader.readTree(body)))); }
        catch (JsonProcessingException ex) { throw BillingValues.invalid("items"); }
    }
}
