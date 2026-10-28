package com.sicost.us5;

import com.sicost.common.api.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.us5.MonthlyShortageModels.*;

@RestController
public class MonthlyShortageController {
    private final MonthlyShortageService service;
    private final MonthlyShortageExportService export;
    private final Clock clock;
    public MonthlyShortageController(MonthlyShortageService service, MonthlyShortageExportService export, Clock clock) {
        this.service = service; this.export = export; this.clock = clock;
    }
    @GetMapping("/api/plan-vs-actual/shortages")
    public ApiResponse<Page> listMonthlyShortages(@RequestParam Map<String, String> query) {
        return ApiResponse.ok(service.list(MonthlyShortageInput.query(query, clock, false)));
    }
    @GetMapping("/api/plan-vs-actual/shortages/export")
    public ResponseEntity<byte[]> exportMonthlyShortages(@RequestParam Map<String, String> query) {
        var file = export.export(MonthlyShortageInput.query(query, clock, true));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8).build().toString()).body(file.bytes());
    }
}
