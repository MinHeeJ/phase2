package com.sicost.receipts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.billing.BillingRules;
import org.springframework.web.bind.annotation.*;
import static com.sicost.receipts.BillingReceiptModels.*;

@RestController
public class BillingReceiptController {
    private final BillingReceiptService service;
    private final ObjectReader reader;
    public BillingReceiptController(BillingReceiptService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private ReceiptInput input(String body) {
        try { return BillingReceiptInput.parse(reader.readTree(body)); }
        catch (JsonProcessingException ex) { throw BillingRules.invalid(null); }
    }
    @GetMapping("/api/billings/{id}/receipts")
    public ApiResponse<ReceiptBillingDetail> listBillingReceipts(@PathVariable long id) {
        return ApiResponse.ok(service.list(id));
    }
    @PostMapping("/api/billings/{id}/receipts")
    public ApiResponse<ReceiptBillingDetail> createBillingReceipt(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.create(id, input(body)));
    }
    @PutMapping("/api/billings/{id}/receipts/{receiptId}")
    public ApiResponse<ReceiptBillingDetail> updateBillingReceipt(@PathVariable long id,
            @PathVariable long receiptId, @RequestBody String body) {
        return ApiResponse.ok(service.update(id, receiptId, input(body)));
    }
    @DeleteMapping("/api/billings/{id}/receipts/{receiptId}")
    public ApiResponse<ReceiptBillingDetail> deleteBillingReceipt(@PathVariable long id, @PathVariable long receiptId) {
        return ApiResponse.ok(service.delete(id, receiptId));
    }
}
