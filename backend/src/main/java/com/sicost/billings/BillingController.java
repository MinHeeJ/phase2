package com.sicost.billings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.BillingValues;
import java.util.HashSet;
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
        catch (JsonProcessingException ex) { throw BillingValues.invalid(null); }
    }
    @GetMapping("/api/billings")
    public ApiResponse<BillingPage> listBillings(@ModelAttribute ListInput query) {
        return ApiResponse.ok(service.list(query));
    }
    @GetMapping("/api/billings/{id}")
    public ApiResponse<BillingDetail> getBilling(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }
    @PostMapping("/api/billings")
    public ApiResponse<BillingDetail> createBilling(@RequestBody String body) {
        return ApiResponse.ok(service.create(BillingInput.create(input(body))));
    }
    @PutMapping("/api/billings/{id}")
    public ApiResponse<BillingDetail> updateBilling(@PathVariable long id, @RequestBody String body) {
        var node = input(body);
        var update = BillingInput.update(node);
        var supplied = new HashSet<String>();
        node.fieldNames().forEachRemaining(supplied::add);
        return ApiResponse.ok(service.update(id, update, supplied));
    }
}
