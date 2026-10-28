package com.sicost.billings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.validation.BillingRules;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.billings.BillingModels.*;

@RestController
public class BillingController {
    private final BillingService service;
    private final ObjectReader reader;
    public BillingController(BillingService service, ObjectMapper json) {
        this.service = service;
        reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private JsonNode input(String body) {
        try { return reader.readTree(body); }
        catch (JsonProcessingException ex) { throw BillingRules.invalid(null); }
    }
    @GetMapping("/api/billings")
    public ApiResponse<Page> listBillings(@RequestParam Map<String, String> params) {
        return ApiResponse.ok(service.list(params));
    }
    @GetMapping("/api/billings/{id}")
    public ApiResponse<Detail> getBilling(@PathVariable long id) { return ApiResponse.ok(service.detail(id)); }
    @PostMapping("/api/billings")
    public ApiResponse<Detail> createBilling(@RequestBody String body) {
        return ApiResponse.ok(service.create(input(body)));
    }
    @PutMapping("/api/billings/{id}")
    public ApiResponse<Detail> updateBilling(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.update(id, input(body)));
    }
}
