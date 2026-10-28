package com.sicost.us1;

import com.sicost.common.api.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.us1.WithdrawalModels.*;

@RestController
public class WithdrawalController {
    private final WithdrawalService service;
    private final WithdrawalExportService exports;
    public WithdrawalController(WithdrawalService service, WithdrawalExportService exports) {
        this.service = service;
        this.exports = exports;
    }
    @GetMapping("/api/withdrawals")
    public ApiResponse<Page> listWithdrawals(@RequestParam Map<String, String> values) {
        var query = WithdrawalInput.parse(values, false);
        var snapshot = service.snapshot(query);
        var rows = snapshot.rows().stream().skip((long) query.page() * query.size()).limit(query.size()).toList();
        return new ApiResponse<>(true, Map.of("today", snapshot.today()),
            new Page(rows, snapshot.rows().size(), query.page(), query.size(), snapshot.summary()));
    }
    @GetMapping("/api/withdrawals/export")
    public ResponseEntity<byte[]> exportWithdrawals(@RequestParam Map<String, String> values) {
        var file = exports.export(values);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .body(file.bytes());
    }
}
