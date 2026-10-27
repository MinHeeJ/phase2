package com.sicost.memos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.memos.ProjectMemoModels.*;

@RestController
public class ProjectMemoController {
    private final ProjectMemoService service;
    private final ObjectReader reader;
    public ProjectMemoController(ProjectMemoService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private MemoInput input(String body) {
        try { return ProjectMemoInput.parse(reader.readTree(body)); }
        catch (JsonProcessingException ex) { throw ApiException.badRequest("입력값을 확인하세요.", null); }
    }
    @PostMapping("/api/projects/{id}/memos")
    public ApiResponse<WeeklyMemo> saveProjectMemo(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.save(id, input(body)));
    }
    @PutMapping("/api/projects/{id}/memos/{memoId}")
    public ApiResponse<WeeklyMemo> updateProjectMemo(@PathVariable long id, @PathVariable long memoId, @RequestBody String body) {
        return ApiResponse.ok(service.update(id, memoId, input(body)));
    }
}
