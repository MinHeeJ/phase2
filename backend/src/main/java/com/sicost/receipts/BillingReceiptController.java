package com.sicost.receipts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.BillingValues;
import org.springframework.web.bind.annotation.*;
import static com.sicost.receipts.BillingReceiptModels.*;

@RestController
public class BillingReceiptController {
    private final BillingReceiptService service;
    private final ObjectReader reader;
    public BillingReceiptController(BillingReceiptService service, ObjectMapper json) {
        this.service = service;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private ReceiptInput input(String body) {
        try { return BillingReceiptInput.parse(reader.readTree(body)); }
        catch (JsonProcessingException | IllegalArgumentException ex) { throw BillingValues.invalid(null); }
    }
    @GetMapping("/api/billings/{id}/receipts")
    public ApiResponse<BillingDetail> listBillingReceipts(@PathVariable String id) {
        return ApiResponse.ok(service.list(BillingReceiptInput.id(id, "id")));
    }
    @PostMapping("/api/billings/{id}/receipts")
    public ApiResponse<BillingDetail> createBillingReceipt(@PathVariable String id,
            @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.create(BillingReceiptInput.id(id, "id"), input(body)));
    }
    @PutMapping("/api/billings/{id}/receipts/{receiptId}")
    public ApiResponse<BillingDetail> updateBillingReceipt(@PathVariable String id,
            @PathVariable String receiptId, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.update(BillingReceiptInput.id(id, "id"),
            BillingReceiptInput.id(receiptId, "receiptId"), input(body)));
    }
    @DeleteMapping("/api/billings/{id}/receipts/{receiptId}")
    public ApiResponse<BillingDetail> deleteBillingReceipt(@PathVariable String id, @PathVariable String receiptId) {
        return ApiResponse.ok(service.delete(BillingReceiptInput.id(id, "id"),
            BillingReceiptInput.id(receiptId, "receiptId")));
    }
}
