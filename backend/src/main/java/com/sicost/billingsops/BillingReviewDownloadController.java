package com.sicost.billingsops;

import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class BillingReviewDownloadController {
    private final BillingReviewDownloadService service;
    public BillingReviewDownloadController(BillingReviewDownloadService service) {this.service=service;}
    @GetMapping("/api/billings/gaps/export")
    public ApiResponse<DownloadArtifact> gaps(@RequestParam Map<String,String> filters) {return ApiResponse.ok(service.gaps(filters));}
    @GetMapping("/api/billings/overdue/export")
    public ApiResponse<DownloadArtifact> overdue(@RequestParam Map<String,String> filters) {return ApiResponse.ok(service.overdue(filters));}
}
