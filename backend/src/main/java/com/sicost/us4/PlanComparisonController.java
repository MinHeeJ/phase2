package com.sicost.us4;

import com.sicost.common.api.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.us4.PlanComparisonModels.*;

@RestController
public class PlanComparisonController {
    private final PlanComparisonService service;
    public PlanComparisonController(PlanComparisonService service) { this.service=service; }
    private int number(Map<String,String> input, String key, int fallback) {
        try { return input.containsKey(key) ? Integer.parseInt(input.get(key)) : fallback; }
        catch (NumberFormatException e) { throw ApiException.badRequest("조회 조건을 확인하세요.",key); }
    }
    private Query query(Map<String,String> input) {
        Long project=null;
        if(input.containsKey("projectId")) {
            try { project=Long.parseLong(input.get("projectId")); }
            catch(NumberFormatException e) { throw ApiException.badRequest("프로젝트를 확인하세요.","projectId"); }
        }
        return new Query(number(input,"year",0),project,input.get("job"));
    }
    @GetMapping("/api/plan-vs-actual")
    public ApiResponse<Page> list(@RequestParam Map<String,String> input) {
        return ApiResponse.ok(service.list(query(input),input.get("gap"),number(input,"page",0),number(input,"size",50)));
    }
    @GetMapping("/api/plan-vs-actual/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String,String> input) {
        var file = service.export(query(input), input.get("gap"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(DownloadArtifact.XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.file_name(), StandardCharsets.UTF_8).build().toString())
            .body(Base64.getDecoder().decode(file.content_base64()));
    }
}
