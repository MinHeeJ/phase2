package com.sicost.assignments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.assignments.AssignmentModels.*;

@RestController
public class AssignmentController {
    private final AssignmentService service;
    private final ObjectMapper json;
    private final Clock clock;
    public AssignmentController(AssignmentService service, ObjectMapper json, Clock clock) {
        this.service = service; this.json = json; this.clock = clock;
    }
    @GetMapping("/api/assignments")
    public ApiResponse<Page> listAssignments(@RequestParam Map<String, String> query) {
        return ApiResponse.ok(service.list(AssignmentInput.query(query, clock)));
    }
    @GetMapping("/api/assignments/{id}")
    public ApiResponse<Detail> getAssignment(@PathVariable long id) { return ApiResponse.ok(service.get(id)); }
    @PostMapping("/api/assignments")
    public ApiResponse<SaveResult> createAssignment(@RequestBody String body) {
        try {
            JsonNode node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body);
            return ApiResponse.ok(service.create(AssignmentInput.parse(node, json)));
        } catch (JsonProcessingException ex) { throw AssignmentInput.error(null); }
    }
}
