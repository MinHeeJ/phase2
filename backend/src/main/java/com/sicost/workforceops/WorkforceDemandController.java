package com.sicost.workforceops;

import com.sicost.common.api.ApiResponse;
import com.sicost.opportunitiesops.OpportunityForecastModels.WorkforcePage;
import com.sicost.opportunitiesops.WorkforceForecastService;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkforceDemandController {
    private final WorkforceForecastService service;
    private final Clock clock;
    public WorkforceDemandController(WorkforceForecastService service, Clock clock) {
        this.service=service;
        this.clock=clock;
    }
    @GetMapping("/api/workforce/forecast")
    public ApiResponse<WorkforcePage> getWorkforceForecast(@RequestParam Map<String,String> values) {
        return ApiResponse.ok(service.forecast(WorkforceDemandInput.parse(values,clock)));
    }
}
