package com.sicost.us5;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.api.DownloadArtifact;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShortageController {
    private final ShortageService service;
    public ShortageController(ShortageService service) { this.service = service; }
    @GetMapping("/api/plan-vs-actual/shortages")
    public ApiResponse<ShortageModels.Page> list(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.list(service.parse(query)));
    }
    @GetMapping("/api/plan-vs-actual/shortages/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String,String> query) {
        var file = service.export(service.parse(query));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(DownloadArtifact.XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.file_name(), StandardCharsets.UTF_8).build().toString())
            .body(Base64.getDecoder().decode(file.content_base64()));
    }
}
