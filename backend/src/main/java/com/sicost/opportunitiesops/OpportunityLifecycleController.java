package com.sicost.opportunitiesops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.projects.ProjectInput;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

@RestController
public class OpportunityLifecycleController {
    public static final Set<String> PERMISSION_KEYS=Set.of("UI-29.read","UI-29.download","UI-29.approve","UI-29.update","UI-29.win");
    private final OpportunityLifecycleService lifecycle;
    private final OpportunityWinService wins;
    private final WorkforceForecastService forecast;
    private final OpportunityExportService exports;
    private final ObjectReader reader;
    public OpportunityLifecycleController(OpportunityLifecycleService lifecycle,OpportunityWinService wins,
        WorkforceForecastService forecast,OpportunityExportService exports,ObjectMapper json){
        this.lifecycle=lifecycle;this.wins=wins;this.forecast=forecast;this.exports=exports;
        this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String body){try{return reader.readTree(body);}catch(JsonProcessingException e){throw OpportunityLifecycleInput.invalid(null);}}
    @GetMapping("/api/opportunities/export")
    public ApiResponse<DownloadArtifact> exportOpportunities(@RequestParam Map<String,String> query){return ApiResponse.ok(exports.export(query));}
    @GetMapping("/api/opportunities/forecast")
    public ApiResponse<OpportunityForecastModels.Page> getOpportunityForecast(@RequestParam Map<String,String> query){return ApiResponse.ok(forecast.opportunityForecast(query));}
    @PostMapping("/api/opportunities/{id}/stage")
    public ApiResponse<Detail> updateOpportunityStage(@PathVariable String id,@RequestBody String body){return ApiResponse.ok(lifecycle.stage(OpportunityLifecycleInput.id(id),OpportunityLifecycleInput.stage(body(body))));}
    @PostMapping("/api/opportunities/{id}/approve")
    public ApiResponse<Detail> updateOpportunityApproval(@PathVariable String id,@RequestBody String body){return ApiResponse.ok(lifecycle.approve(OpportunityLifecycleInput.id(id),OpportunityLifecycleInput.approval(body(body))));}
    @PostMapping("/api/opportunities/{id}/reject")
    public ApiResponse<Detail> updateOpportunityRejection(@PathVariable String id,@RequestBody String body){return ApiResponse.ok(lifecycle.reject(OpportunityLifecycleInput.id(id),OpportunityLifecycleInput.reason(body(body))));}
    @PostMapping("/api/opportunities/{id}/lose")
    public ApiResponse<Detail> updateOpportunityLoss(@PathVariable String id,@RequestBody String body){return ApiResponse.ok(lifecycle.lose(OpportunityLifecycleInput.id(id),OpportunityLifecycleInput.reason(body(body))));}
    @PostMapping("/api/opportunities/{id}/disable")
    public ApiResponse<Detail> updateOpportunityDisable(@PathVariable String id){return ApiResponse.ok(lifecycle.disable(OpportunityLifecycleInput.id(id)));}
    @PostMapping("/api/opportunities/{id}/win")
    public ApiResponse<WinResult> createOpportunityWin(@PathVariable String id,@RequestBody String body){
        long key=OpportunityLifecycleInput.id(id);var n=body(body);OpportunityLifecycleInput.fields(n,Set.of("project"));
        return ApiResponse.ok(wins.win(key,ProjectInput.parse(n.get("project"),false)));
    }
}
