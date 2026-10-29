package com.sicost.dispatchattendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.billing.BillingRules;
import org.springframework.web.bind.annotation.*;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

@RestController
public class DispatchAttendanceController {
    private final DispatchAttendanceService service;
    private final ObjectReader reader;
    public DispatchAttendanceController(DispatchAttendanceService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    @GetMapping("/api/dispatch-attendance")
    public ApiResponse<Result> getDispatchAttendance(@RequestParam String month, @RequestParam long projectId) {
        return ApiResponse.ok(service.getDispatchAttendance(month, projectId));
    }
    @PutMapping("/api/dispatch-attendance")
    public ApiResponse<Result> saveDispatchAttendance(@RequestParam String month, @RequestParam long projectId,
            @RequestBody String body) {
        try {
            return ApiResponse.ok(service.saveDispatchAttendance(month, projectId,
                DispatchAttendanceInput.parse(reader.readTree(body))));
        } catch (JsonProcessingException ex) { throw BillingRules.invalid("items"); }
    }
}
