package com.sicost.dispatchattendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.BillingValues;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    public ApiResponse<Result> getDispatchAttendance(@RequestParam String month, @RequestParam Long projectId) {
        return ApiResponse.ok(service.get(month, projectId));
    }
    @PutMapping("/api/dispatch-attendance")
    public ApiResponse<Result> saveDispatchAttendance(@RequestParam String month,
            @RequestParam Long projectId, @RequestBody String body) {
        return ApiResponse.ok(service.save(month, projectId, input(body)));
    }
    private Batch input(String body) {
        try {
            JsonNode root = reader.readTree(body);
            if (root == null || !root.isObject() || !root.path("items").isArray()) {
                throw BillingValues.invalid("items");
            }
            var items = new ArrayList<Input>();
            for (var row : root.path("items")) {
                String prefix = "items[" + items.size() + "].";
                if (!row.isObject()) throw BillingValues.invalid("items[" + items.size() + "]");
                items.add(new Input(id(row, "asgn_id", prefix), id(row, "dev_id", prefix),
                    number(row, "work_day_qty", prefix), number(row, "rglr_tm_qty", prefix),
                    number(row, "ovtm_tm_qty", prefix), number(row, "mth_work_day_qty", prefix)));
            }
            return new Batch(items);
        } catch (JsonProcessingException ex) { throw BillingValues.invalid("items"); }
    }
    private Long id(JsonNode row, String key, String prefix) {
        var value = row.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw BillingValues.invalid(prefix + key);
        return value.longValue();
    }
    private BigDecimal number(JsonNode row, String key, String prefix) {
        var value = row.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber()) throw BillingValues.invalid(prefix + key);
        return value.decimalValue();
    }
}
