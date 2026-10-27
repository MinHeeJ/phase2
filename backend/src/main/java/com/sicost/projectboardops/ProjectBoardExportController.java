package com.sicost.projectboardops;

import com.sicost.common.api.DownloadArtifact;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProjectBoardExportController {
    private final ProjectBoardExportService service;
    public ProjectBoardExportController(ProjectBoardExportService service) { this.service=service; }
    @GetMapping("/api/project-board/export")
    public ResponseEntity<byte[]> getProjectBoardExport(@RequestParam Map<String,String> parameters) {
        var file=service.export(parameters);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(DownloadArtifact.XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                .filename(file.file_name(),StandardCharsets.UTF_8).build().toString())
            .body(Base64.getDecoder().decode(file.content_base64()));
    }
}
