package com.sicost.apiops;

import com.sicost.common.api.ApiResponse;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.apiops.ProjectBoardModels.*;

@RestController
public class ProjectBoardController {
    private final ProjectBoardService board;
    private final ProgressParameterService parameters;
    public ProjectBoardController(ProjectBoardService board, ProgressParameterService parameters) {
        this.board = board; this.parameters = parameters;
    }
    @GetMapping("/api/project-board")
    public ApiResponse<BoardData> getProjectBoard(@RequestParam Map<String, String> input) {
        return ApiResponse.ok(board.get(input));
    }
    @GetMapping("/api/params")
    public ApiResponse<List<OperatingParam>> getParams(@RequestParam(required = false) String month) {
        return ApiResponse.ok(parameters.get(month));
    }
}
