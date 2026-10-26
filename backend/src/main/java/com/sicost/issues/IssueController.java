package com.sicost.issues;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.issues.IssueModels.*;

@RestController
public class IssueController {
    private final IssueService service;
    private final ObjectReader reader;
    public IssueController(IssueService service, ObjectMapper json) {
        this.service=service;
        this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String body) {
        try { return reader.readTree(body); }
        catch (JsonProcessingException ex) { throw ApiException.badRequest("입력값을 확인하세요.",null); }
    }
    @GetMapping("/api/issues")
    public ApiResponse<IssuesData> listIssues(@RequestParam Map<String,String> params) {
        return ApiResponse.ok(service.list(params));
    }
    @PostMapping("/api/issues")
    public ApiResponse<IssueRow> createIssue(@RequestBody String body) {
        return ApiResponse.ok(service.create(IssueInputParser.create(body(body))));
    }
    @PutMapping("/api/issues/{issueId}")
    public ApiResponse<IssueRow> updateIssue(@PathVariable long issueId, @RequestBody String body) {
        return ApiResponse.ok(service.update(issueId,IssueInputParser.update(body(body))));
    }
}
