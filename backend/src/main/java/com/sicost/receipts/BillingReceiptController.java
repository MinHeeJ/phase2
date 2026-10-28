package com.sicost.receipts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.BillingRules;
import org.springframework.web.bind.annotation.*;
import static com.sicost.receipts.BillingReceiptModels.*;

@RestController
public class BillingReceiptController {
    private final BillingReceiptService service;
    private final ObjectReader reader;
    public BillingReceiptController(BillingReceiptService service, ObjectMapper json) {
        this.service = service;
        reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private ReceiptInput input(String body, boolean update) {
        try { return BillingReceiptInput.parse(reader.readTree(body), update); }
        catch (JsonProcessingException ex) { throw BillingRules.invalid(null); }
    }
    @GetMapping("/api/billings/{id}/receipts")
    public ApiResponse<ReceiptDetail> listBillingReceipts(@PathVariable long id) {
        return ApiResponse.ok(service.listBillingReceipts(id));
    }
    @PostMapping("/api/billings/{id}/receipts")
    public ApiResponse<ReceiptDetail> createBillingReceipt(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.createBillingReceipt(id, input(body, false)));
    }
    @PutMapping("/api/billings/{id}/receipts/{receiptId}")
    public ApiResponse<ReceiptDetail> updateBillingReceipt(@PathVariable long id,
            @PathVariable long receiptId, @RequestBody String body) {
        return ApiResponse.ok(service.updateBillingReceipt(id, receiptId, input(body, true)));
    }
    @DeleteMapping("/api/billings/{id}/receipts/{receiptId}")
    public ApiResponse<ReceiptDetail> deleteBillingReceipt(@PathVariable long id, @PathVariable long receiptId) {
        return ApiResponse.ok(service.deleteBillingReceipt(id, receiptId));
    }
}
