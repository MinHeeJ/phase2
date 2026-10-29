package com.sicost.costsops;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.costsops.RenegotiationModels.*;

@RestController
public class RenegotiationController {
    private final RenegotiationService service;
    public RenegotiationController(RenegotiationService service) { this.service = service; }
    @GetMapping("/api/costs/renegotiations")
    public ApiResponse<Data> listCostRenegotiations(@RequestParam Map<String, String> parameters) {
        return ApiResponse.ok(service.list(RenegotiationQuery.parse(parameters)));
    }
}
