package com.sicost.dashboardops;

import com.sicost.common.security.Sensitive;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class DashboardExportController {
    private final DashboardExportService exports;
    public DashboardExportController(DashboardExportService exports) { this.exports=exports; }
    @Sensitive
    @GetMapping("/api/dashboard/export")
    public ResponseEntity<byte[]> exportDashboard(@RequestParam(required=false) String month) {
        var download=exports.download(month);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                .filename(download.filename(),StandardCharsets.UTF_8).build().toString())
            .body(download.bytes());
    }
}
