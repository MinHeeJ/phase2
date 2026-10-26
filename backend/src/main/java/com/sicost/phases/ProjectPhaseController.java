package com.sicost.phases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.*;
import static com.sicost.phases.ProjectPhaseModels.*;

@RestController
public class ProjectPhaseController {
    private final ProjectPhaseService service;
    private final ObjectReader reader;
    public ProjectPhaseController(ProjectPhaseService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private LocalDate date(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || !value.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw ApiException.badRequest("날짜를 확인하세요.", field);
        }
        try { return LocalDate.parse(value.textValue()); }
        catch (DateTimeParseException ex) { throw ApiException.badRequest("날짜를 확인하세요.", field); }
    }
    private PeriodInput input(String body) {
        try {
            JsonNode node = reader.readTree(body);
            if (node == null || !node.isObject()) throw ApiException.badRequest("입력값을 확인하세요.", null);
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!field.equals("plan_bgn_dt") && !field.equals("plan_end_dt")) throw ApiException.badRequest("변경할 수 없는 항목입니다.", field);
            }
            return new PeriodInput(date(node, "plan_bgn_dt"), date(node, "plan_end_dt"));
        } catch (JsonProcessingException ex) { throw ApiException.badRequest("입력값을 확인하세요.", null); }
    }
    @PutMapping("/api/projects/{id}/phases/{phsId}")
    public ApiResponse<Phase> updateProjectPhase(@PathVariable long id, @PathVariable long phsId, @RequestBody String body) {
        return ApiResponse.ok(service.update(id, phsId, input(body)));
    }
    @PostMapping("/api/projects/{id}/phases/{phsId}/complete")
    public ApiResponse<Phase> updateProjectPhaseCompletion(@PathVariable long id, @PathVariable long phsId) {
        return ApiResponse.ok(service.complete(id, phsId));
    }
    @PostMapping("/api/projects/{id}/phases/{phsId}/uncomplete")
    public ApiResponse<Phase> updateProjectPhaseUncompletion(@PathVariable long id, @PathVariable long phsId) {
        return ApiResponse.ok(service.uncomplete(id, phsId));
    }
}
