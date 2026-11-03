package com.sicost.workforce;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.workforce.WorkforceForecastModels.*;

@RestController
public class WorkforceController {
    private final WorkforceForecastService service;
    public WorkforceController(WorkforceForecastService service){this.service=service;}
    @GetMapping("/api/workforce/forecast") public ApiResponse<ForecastPage> forecast(@RequestParam Map<String,String> parameters){return ApiResponse.ok(service.forecast(parameters));}
    @GetMapping("/api/candidates/shortages") public ApiResponse<ShortagePage> shortages(@RequestParam Map<String,String> parameters){return ApiResponse.ok(service.shortages(parameters));}
}
