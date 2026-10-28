package com.sicost.us4;

import com.sicost.common.api.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.us4.PlanComparisonModels.*;

@RestController
public class PlanComparisonController {
    private final PlanComparisonService service;
    private final PlanComparisonExportService export;
    public PlanComparisonController(PlanComparisonService service, PlanComparisonExportService export) {
        this.service = service;
        this.export = export;
    }
    @GetMapping("/api/plan-vs-actual")
    public ApiResponse<Page> listPlanComparison(@RequestParam Map<String, String> query) {
        var result = service.list(PlanComparisonInput.query(query, false));
        return new ApiResponse<>(true, Map.of("today", result.today(), "planGapTolerance", result.tolerance(),
            "projects", result.projects()), result.data());
    }
    @GetMapping("/api/plan-vs-actual/export")
    public ResponseEntity<byte[]> exportPlanComparison(@RequestParam Map<String, String> query) {
        var file = export.export(query);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .body(file.bytes());
    }
}
