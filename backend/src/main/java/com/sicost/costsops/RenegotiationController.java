package com.sicost.costsops;

import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.costsops.RenegotiationModels.*;

@RestController
public class RenegotiationController {
    private final RenegotiationService service;
    private final RenegotiationExportService exports;
    public RenegotiationController(RenegotiationService service, RenegotiationExportService exports) {
        this.service = service;
        this.exports = exports;
    }
    @GetMapping(value = "/api/costs/renegotiations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Result> listCostRenegotiations(@RequestParam Map<String, String> input) {
        return ApiResponse.ok(service.list(input));
    }
    @GetMapping(value = "/api/costs/renegotiations",
        produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> downloadCostRenegotiations(@RequestParam Map<String, String> input) {
        var file = exports.export(input);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
            .body(file.bytes());
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> error(Exception ex, HttpServletRequest request) {
        var handler = new ApiExceptionHandler();
        var result = ex instanceof ApiException application
            ? handler.application(application, request) : handler.unexpected(ex, request);
        return ResponseEntity.status(result.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
            .body(result.getBody());
    }
}
