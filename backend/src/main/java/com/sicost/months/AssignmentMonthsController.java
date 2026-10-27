package com.sicost.months;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.months.AssignmentMonthsModels.*;

@RestController
public class AssignmentMonthsController {
    private final AssignmentMonthsService service;
    private final ObjectReader reader;

    public AssignmentMonthsController(AssignmentMonthsService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    @GetMapping("/api/assignments/{id}/months")
    public ApiResponse<MonthsResult> getAssignmentMonths(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }
    @PutMapping("/api/assignments/{id}/months")
    public ApiResponse<MonthsResult> saveAssignmentMonths(@PathVariable long id, @RequestBody String body) {
        MonthsInput input;
        try { input = AssignmentMonthsInput.parse(reader.readTree(body)); }
        catch (JsonProcessingException ex) { throw AssignmentMonthsInput.invalid(null); }
        return ApiResponse.ok(service.save(id, input));
    }
}
