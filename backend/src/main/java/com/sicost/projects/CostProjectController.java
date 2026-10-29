package com.sicost.projects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.costallocation.CostAnalysisInput;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.projects.CostProjectModels.*;

@RestController
public class CostProjectController {
    private final CostProjectService service;
    private final CostProjectExportService exports;
    private final ObjectReader reader;
    public CostProjectController(CostProjectService service,CostProjectExportService exports,ObjectMapper json) {
        this.service=service;this.exports=exports;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/costs/projects")
    public Object listCostProjects(@RequestParam Map<String,String> input,
        @RequestHeader(value="Accept",required=false) String accept) {
        var query=service.query(input);
        if(accept!=null && accept.contains(DownloadArtifact.XLSX)) {
            byte[] bytes=exports.download(query);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(DownloadArtifact.XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                    .filename("프로젝트원가분석_"+query.year()+".xlsx",StandardCharsets.UTF_8).build().toString()).body(bytes);
        }
        return ApiResponse.ok(service.list(query));
    }
    @GetMapping("/api/costs/projects/{id}")
    public ApiResponse<Detail> getCostProject(@PathVariable String id,@RequestParam Map<String,String> input) {
        return ApiResponse.ok(service.detail(CostAnalysisInput.id(id,"id"),service.query(input)));
    }
    @GetMapping("/api/costs/projects/{id}/causes")
    public ApiResponse<Causes> getCostProjectCauses(@PathVariable String id,@RequestParam Map<String,String> input) {
        return ApiResponse.ok(service.causes(CostAnalysisInput.id(id,"id"),service.query(input)));
    }
    @PostMapping("/api/costs/projects/{id}/simulate")
    public ApiResponse<Simulation> simulateCostProject(@PathVariable String id,@RequestBody(required=false) String body) {
        long projectId=CostAnalysisInput.id(id,"id");
        if(body==null || body.isBlank())throw CostAnalysisInput.invalid("body");
        try {return ApiResponse.ok(service.simulate(projectId,reader.readTree(body)));}
        catch(JsonProcessingException ex) {throw CostAnalysisInput.invalid("body");}
    }
}
