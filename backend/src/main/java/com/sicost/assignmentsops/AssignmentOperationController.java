package com.sicost.assignmentsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.assignmentsops.AssignmentOperationModels.*;

@RestController
public class AssignmentOperationController {
    private final AssignmentOperationService service;
    private final AssignmentExportService export;
    private final ObjectReader reader;
    public AssignmentOperationController(AssignmentOperationService service, AssignmentExportService export, ObjectMapper json) {
        this.service = service;
        this.export = export;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private JsonNode body(String body) {
        try { return reader.readTree(body); }
        catch (JsonProcessingException e) { throw AssignmentOperationInput.error(null); }
    }
    @PostMapping("/api/assignments/check")
    public ApiResponse<CheckResult> checkAssignments(@RequestBody String body) {
        return ApiResponse.ok(service.check(AssignmentOperationInput.proposal(body(body))));
    }
    @PostMapping("/api/assignments/cancel")
    public ApiResponse<BatchResult> saveAssignmentCancellation(@RequestBody String body) {
        return ApiResponse.ok(service.cancel(AssignmentOperationInput.batch(body(body), false)));
    }
    @PostMapping("/api/assignments/ext-chance")
    public ApiResponse<BatchResult> saveAssignmentExtChance(@RequestBody String body) {
        return ApiResponse.ok(service.chance(AssignmentOperationInput.batch(body(body), true)));
    }
    @PostMapping("/api/assignments/{id}/replace")
    public ApiResponse<ReplacementResult> createAssignmentReplacement(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.replace(id, AssignmentOperationInput.replacement(body(body))));
    }
    @GetMapping("/api/assignments/export")
    public ResponseEntity<byte[]> getAssignmentsExport(@RequestParam Map<String, String> query) {
        var file = export.export(query);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .body(file.bytes());
    }
}
