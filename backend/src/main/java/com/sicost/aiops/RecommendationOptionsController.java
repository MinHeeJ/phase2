package com.sicost.aiops;

import com.sicost.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.sicost.aiops.RecommendationOptionsModels.*;

@RestController
public class RecommendationOptionsController {
    private final RecommendationOptionsService service;
    public RecommendationOptionsController(RecommendationOptionsService service) { this.service=service; }
    @GetMapping("/api/ai/staff-recommendation/options")
    public ApiResponse<Options> getStaffRecommendationOptions(HttpServletRequest request, @RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.get(request,query));
    }
}
