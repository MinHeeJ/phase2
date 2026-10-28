package com.sicost.billingsops;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/billings")
public class BillingExportController {
    private final BillingExportService exports;
    public BillingExportController(BillingExportService exports) { this.exports=exports; }
    private ResponseEntity<byte[]> file(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"billing.xlsx\"").body(bytes);
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> getBillingsExport(@RequestParam Map<String,String> parameters) {
        return file(exports.billings(parameters));
    }
    @GetMapping("/gaps/export")
    public ResponseEntity<byte[]> getBillingGapsExport(@RequestParam Map<String,String> parameters) {
        return file(exports.gaps(BillingController.gapRequest(parameters)));
    }
    @GetMapping("/overdue/export")
    public ResponseEntity<byte[]> getBillingOverdueExport(@RequestParam Map<String,String> parameters) {
        return file(exports.overdue(BillingController.overdueRequest(parameters)));
    }
}
