package com.sicost.common.health;

import com.sicost.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    public ApiResponse<Void> getHealth() {
        return ApiResponse.ok();
    }
}
