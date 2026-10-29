package com.sicost.projects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.BillingValues;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.projects.CostProjectModels.*;

@RestController
public class CostProjectController {
    private final CostProjectService projects;
    private final CostProjectExportService exports;
    private final ObjectReader reader;
    private final Clock clock;
    public CostProjectController(CostProjectService projects,CostProjectExportService exports,ObjectMapper json,Clock clock) {
        this.projects=projects;this.exports=exports;this.clock=clock;
        this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping(value="/api/costs/projects",produces=MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ProjectList> listCostProjects(@RequestParam Map<String,String> q) {
        return ApiResponse.ok(projects.list(CostProjectInput.query(q,clock)));
    }
    @GetMapping(value="/api/costs/projects",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> downloadCostProjects(@RequestParam Map<String,String> q) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                .filename("프로젝트원가분석.xlsx",StandardCharsets.UTF_8).build().toString())
            .body(exports.download(CostProjectInput.query(q,clock)));
    }
    @GetMapping("/api/costs/projects/{id}")
    public ApiResponse<Detail> getCostProject(@PathVariable String id,@RequestParam Map<String,String> q) {
        return ApiResponse.ok(projects.detail(CostProjectInput.id(id),CostProjectInput.year(q,clock)));
    }
    @GetMapping("/api/costs/projects/{id}/causes")
    public ApiResponse<Causes> getCostProjectCauses(@PathVariable String id,@RequestParam Map<String,String> q) {
        return ApiResponse.ok(projects.causes(CostProjectInput.id(id),CostProjectInput.year(q,clock)));
    }
    @PostMapping("/api/costs/projects/{id}/simulate")
    public ApiResponse<Simulation> simulateCostProject(@PathVariable String id,@RequestBody String body) {
        try {
            return ApiResponse.ok(projects.simulate(CostProjectInput.id(id),CostProjectInput.simulation(reader.readTree(body))));
        } catch (JsonProcessingException ex) { throw BillingValues.invalid(null); }
    }
}
