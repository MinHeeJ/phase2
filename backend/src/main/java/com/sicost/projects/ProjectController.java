package com.sicost.projects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.validation.ProjectNumbers;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import static com.sicost.projects.ProjectModels.*;

@RestController
public class ProjectController {
    private final ProjectService service;
    private final ObjectReader reader;
    public ProjectController(ProjectService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private ProjectSave input(String body, boolean update) {
        try { return ProjectInput.parse(reader.readTree(body),update); }
        catch (JsonProcessingException e) { throw ApiException.badRequest("입력값을 확인하세요.",null); }
    }
    @GetMapping("/api/projects")
    public ApiResponse<ProjectPage> listProjects(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.list(query));
    }
    // GET-only path contract: oneOf positive int64 or string matching ^P-[0-9]{4,5}$ (D17).
    // PUT retains numeric int64; no list/status/use filter applies to this number lookup.
    @GetMapping("/api/projects/{id}")
    public ApiResponse<ProjectDetail> getProject(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }
    @PostMapping("/api/projects")
    public ApiResponse<ProjectDetail> createProject(@RequestBody String body) {
        var input = input(body,false);
        try { return ApiResponse.ok(service.create(input)); }
        catch (DuplicateKeyException e) { throw ProjectNumbers.duplicate(); }
    }
    @PutMapping("/api/projects/{id}")
    public ApiResponse<ProjectDetail> updateProject(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.update(id,input(body,true)));
    }
}
