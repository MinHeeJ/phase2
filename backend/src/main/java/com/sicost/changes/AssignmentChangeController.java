package com.sicost.changes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.changes.AssignmentChangeModels.*;

@RestController
public class AssignmentChangeController {
    private final AssignmentChangeService service;
    private final ObjectReader reader;
    public AssignmentChangeController(AssignmentChangeService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String body) {
        try { return reader.readTree(body); }
        catch (JsonProcessingException e) { throw ApiException.badRequest("입력값을 확인하세요.", null); }
    }
    @GetMapping("/api/assignments/{id}/changes")
    public ApiResponse<Changes> listAssignmentChanges(@PathVariable long id,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok(service.list(id, type));
    }
    @PostMapping("/api/assignments/{id}/changes")
    public ApiResponse<SaveResult> createAssignmentChange(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.create(id, AssignmentChangeInput.parse(body(body))));
    }
    @PostMapping("/api/assignments/{id}/changes/{chgId}/fix")
    public ApiResponse<FixResult> createAssignmentFix(@PathVariable long id, @PathVariable long chgId,
            @RequestBody String body) {
        return ApiResponse.ok(service.fix(id, chgId, AssignmentChangeInput.fix(body(body))));
    }
}
