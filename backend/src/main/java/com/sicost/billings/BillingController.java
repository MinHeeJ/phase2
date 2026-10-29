package com.sicost.billings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.billing.BillingRules;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.billings.BillingModels.*;

@RestController
public class BillingController {
    private final BillingService service;
    private final ObjectReader reader;
    public BillingController(BillingService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private Input input(String body, boolean create) {
        try { return BillingInput.parse(reader.readTree(body), create); }
        catch (JsonProcessingException ex) { throw BillingRules.invalid(null); }
    }
    @GetMapping("/api/billings")
    public ApiResponse<BillingPage> listBillings(@RequestParam Map<String, String> filters) {
        return ApiResponse.ok(service.list(filters));
    }
    @GetMapping("/api/billings/{id}")
    public ApiResponse<BillingDetail> getBilling(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }
    @PostMapping("/api/billings")
    public ApiResponse<BillingDetail> createBilling(@RequestBody String body) {
        return ApiResponse.ok(service.create(input(body, true)));
    }
    @PutMapping("/api/billings/{id}")
    public ApiResponse<BillingDetail> updateBilling(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.update(id, input(body, false)));
    }
}
