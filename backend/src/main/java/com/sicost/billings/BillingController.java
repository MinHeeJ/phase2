package com.sicost.billings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.BillingValues;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.billings.BillingModels.*;

@RestController
public class BillingController {
    private final BillingService service;
    private final ObjectReader reader;
    private final Clock clock;
    public BillingController(BillingService service, ObjectMapper json, Clock clock) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = clock;
    }
    private Input input(String body, boolean create) {
        try { return BillingInput.parse(reader.readTree(body), create); }
        catch (JsonProcessingException ex) { throw BillingValues.invalid(null); }
    }
    @GetMapping("/api/billings")
    public ApiResponse<BillingPage> listBillings(@RequestParam Map<String, String> query) {
        return ApiResponse.ok(service.list(BillingQuery.parse(query, clock)));
    }
    @GetMapping("/api/billings/{id:[0-9]+}")
    public ApiResponse<BillingDetail> getBilling(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }
    @PostMapping("/api/billings")
    public ApiResponse<BillingDetail> createBilling(@RequestBody String body) {
        return ApiResponse.ok(service.create(input(body, true)));
    }
    @PutMapping("/api/billings/{id:[0-9]+}")
    public ApiResponse<BillingDetail> updateBilling(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.update(id, input(body, false)));
    }
}
