package com.sicost.people;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.people.PeopleCostModels.*;

@RestController
public class PeopleCostController {
    private final PeopleCostService service;
    public PeopleCostController(PeopleCostService service) { this.service=service; }
    @GetMapping("/api/costs/people")
    public ApiResponse<People> listCostPeople(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.list(query));
    }
    @GetMapping("/api/costs/people/{devId}/breakdown")
    public ApiResponse<Breakdown> getCostPersonBreakdown(@PathVariable String devId,@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.breakdown(devId,query));
    }
}
