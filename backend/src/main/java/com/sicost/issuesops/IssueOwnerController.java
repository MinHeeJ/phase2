package com.sicost.issuesops;

import com.sicost.common.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import static com.sicost.issuesops.IssueOwnerModels.*;

@RestController
public class IssueOwnerController {
    private final IssueOwnerService service;
    public IssueOwnerController(IssueOwnerService service) { this.service = service; }

    @GetMapping("/api/issues/owner-options")
    public ApiResponse<List<IssueOwnerOption>> getIssueOwnerOptions(@RequestParam long projectId) {
        return ApiResponse.ok(service.options(projectId));
    }
}
