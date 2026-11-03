package com.sicost.withdrawalsops;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.withdrawalsops.NextSlotModels.*;

@RestController
public class NextSlotController {
    private final NextSlotService service;
    public NextSlotController(NextSlotService service) { this.service = service; }
    @GetMapping("/api/withdrawals/{asgnId}/next-slots")
    public ApiResponse<NextSlotsResult> getWithdrawalNextSlots(@PathVariable String asgnId,
            @RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.get(NextSlotInput.parse(asgnId, query)));
    }
}
