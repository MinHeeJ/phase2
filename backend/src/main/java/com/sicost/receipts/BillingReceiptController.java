package com.sicost.receipts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.validation.BillingValues;
import java.util.Set;
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
        try {
            JsonNode node = reader.readTree(body);
            if (node == null || !node.isObject()) throw BillingValues.invalid(null);
            node.fieldNames().forEachRemaining(key -> {
                if (!Set.of("receipt_id", "rcpt_dt", "rcpt_amt", "rmrk_desc").contains(key)) throw BillingValues.invalid(key);
            });
            var date = node.path("rcpt_dt");
            if (!date.isTextual()) throw BillingValues.invalid("rcpt_dt");
            var amount = node.path("rcpt_amt");
            if (!amount.isIntegralNumber() || !amount.canConvertToLong()) throw BillingValues.invalid("rcpt_amt");
            var remark = node.path("rmrk_desc");
            if (!remark.isMissingNode() && !remark.isNull() && !remark.isTextual()) throw BillingValues.invalid("rmrk_desc");
            return new ReceiptInput(BillingValues.date(date.textValue(), "rcpt_dt"),
                amount.longValue(), remark.isTextual() ? remark.textValue() : null);
        } catch (JsonProcessingException ex) { throw BillingValues.invalid(null); }
    }
    @GetMapping("/api/billings/{id}/receipts")
    public ApiResponse<ReceiptDetail> listBillingReceipts(@PathVariable long id) {
        return ApiResponse.ok(service.list(id));
    }
    @PostMapping("/api/billings/{id}/receipts")
    public ApiResponse<ReceiptDetail> createBillingReceipt(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.create(id, input(body)));
    }
    @PutMapping("/api/billings/{id}/receipts/{receiptId}")
    public ApiResponse<ReceiptDetail> updateBillingReceipt(@PathVariable long id,
            @PathVariable long receiptId, @RequestBody String body) {
        return ApiResponse.ok(service.update(id, receiptId, input(body)));
    }
    @DeleteMapping("/api/billings/{id}/receipts/{receiptId}")
    public ApiResponse<ReceiptDetail> deleteBillingReceipt(@PathVariable long id, @PathVariable long receiptId) {
        return ApiResponse.ok(service.delete(id, receiptId));
    }
}
