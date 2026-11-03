package com.sicost.tempassignments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.tempassignments.TempAssignmentModels.*;

@RestController
public class TempAssignmentController {
    private final TempAssignmentService service;
    private final ObjectReader reader;
    public TempAssignmentController(TempAssignmentService service,ObjectMapper json) {
        this.service=service; this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/temp-assignments")
    public ApiResponse<TempAssignmentPage> listTempAssignments(@RequestParam Map<String,String> parameters) {
        return ApiResponse.ok(service.list(parameters));
    }
    @PostMapping("/api/temp-assignments")
    public ApiResponse<TempAssignment> createTempAssignment(@RequestBody String body) {
        try { return ApiResponse.ok(service.create(TempAssignmentInput.parse(reader.readTree(body)))); }
        catch(JsonProcessingException e) { throw TempAssignmentInput.error(null); }
    }
    @DeleteMapping("/api/temp-assignments/{id}")
    public ApiResponse<TempAssignment> updateTempAssignmentRelease(@PathVariable long id) {
        return ApiResponse.ok(service.release(TempAssignmentInput.id(id)));
    }
}
