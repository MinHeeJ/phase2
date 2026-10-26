package com.sicost.contractchanges;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.contractchanges.ProjectContractChangeModels.*;

@RestController
public class ProjectContractChangeController {
    private final ProjectContractChangeService service;
    private final ObjectReader reader;
    public ProjectContractChangeController(ProjectContractChangeService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private ChangeInput input(String body) {
        try { return ProjectContractChangeInput.parse(reader.readTree(body)); }
        catch (JsonProcessingException ex) { throw ApiException.badRequest("입력값을 확인하세요.", null); }
    }
    @GetMapping("/api/projects/{id}/contract-changes")
    public ApiResponse<Changes> listProjectContractChanges(@PathVariable long id) {
        return ApiResponse.ok(service.list(id));
    }
    @PostMapping("/api/projects/{id}/contract-changes")
    public ApiResponse<ChangeRow> createProjectContractChange(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.create(id, input(body)));
    }
    @PutMapping("/api/projects/{id}/contract-changes/{chgId}")
    public ApiResponse<ChangeRow> updateProjectContractChange(@PathVariable long id,
            @PathVariable long chgId, @RequestBody String body) {
        return ApiResponse.ok(service.update(id, chgId, input(body)));
    }
    @DeleteMapping("/api/projects/{id}/contract-changes/{chgId}")
    public ApiResponse<Removal> updateProjectContractChangeRemoval(@PathVariable long id, @PathVariable long chgId) {
        return ApiResponse.ok(service.delete(id, chgId));
    }
    @PostMapping("/api/projects/{id}/contract-changes/{chgId}/confirm")
    public ApiResponse<ChangeRow> updateProjectContractChangeConfirmation(@PathVariable long id, @PathVariable long chgId) {
        return ApiResponse.ok(service.confirm(id, chgId));
    }
}
