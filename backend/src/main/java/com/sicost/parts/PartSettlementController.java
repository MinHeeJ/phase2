package com.sicost.parts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.costallocation.CostAnalysisInput;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.parts.PartSettlementModels.*;

@RestController
public class PartSettlementController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final PartSettlementService service;
    private final PartSettlementExportService exports;
    private final ObjectReader reader;
    public PartSettlementController(PartSettlementService service, PartSettlementExportService exports, ObjectMapper json) {
        this.service = service;
        this.exports = exports;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/settlements/parts")
    public ResponseEntity<?> listPartSettlements(@RequestParam Map<String, String> input,
        @RequestHeader(value = "Accept", required = false) String accept) {
        if (accept != null && accept.contains(XLSX)) {
            var file = exports.export(input);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
                    + URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20"))
                .body(file.bytes());
        }
        return ResponseEntity.ok(ApiResponse.ok(service.list(input)));
    }
    @GetMapping("/api/settlements/parts/{partId}")
    public ApiResponse<Detail> getPartSettlement(@PathVariable String partId, @RequestParam Map<String, String> input) {
        return ApiResponse.ok(service.get(PartSettlementInput.key(partId, input.get("month")),
            PartSettlementInput.view(input.get("view"))));
    }
    @PutMapping("/api/settlements/parts/{partId}/card")
    public ApiResponse<Detail> updatePartSettlementCard(@PathVariable String partId,
        @RequestParam Map<String, String> input, @RequestBody(required = false) String body) {
        var key = PartSettlementInput.key(partId, input.get("month"));
        return ApiResponse.ok(service.card(key, PartSettlementInput.card(parse(body))));
    }
    @PostMapping("/api/settlements/parts/{partId}/confirm")
    public ApiResponse<Detail> confirmPartSettlement(@PathVariable String partId,
        @RequestParam Map<String, String> input, @RequestBody(required = false) String body) {
        var key = PartSettlementInput.key(partId, input.get("month"));
        if (body != null && !body.isBlank()) PartSettlementInput.confirmation(parse(body));
        return ApiResponse.ok(service.confirm(key));
    }
    @PostMapping("/api/settlements/parts/{partId}/cancel")
    public ApiResponse<Detail> cancelPartSettlement(@PathVariable String partId,
        @RequestParam Map<String, String> input, @RequestBody(required = false) String body) {
        var key = PartSettlementInput.key(partId, input.get("month"));
        return ApiResponse.ok(service.cancel(key, PartSettlementInput.reason(parse(body))));
    }
    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) throw CostAnalysisInput.invalid(null);
        try { return reader.readTree(body); }
        catch (JsonProcessingException ex) { throw CostAnalysisInput.invalid(null); }
    }
}
