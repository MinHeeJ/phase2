package com.sicost.board;

import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.board.WorkforceBoardModels.*;

@RestController
public class WorkforceBoardController {
    private final WorkforceBoardService service;
    private final WorkforceBoardExportService exports;
    public WorkforceBoardController(WorkforceBoardService service, WorkforceBoardExportService exports) {
        this.service=service; this.exports=exports;
    }
    @GetMapping("/api/workforce/board")
    public ApiResponse<BoardPage> getWorkforceBoard(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.getWorkforceBoard(query));
    }
    @GetMapping("/api/workforce/board/export")
    public ApiResponse<DownloadArtifact> exportWorkforceBoard(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(exports.exportWorkforceBoard(query));
    }
}
