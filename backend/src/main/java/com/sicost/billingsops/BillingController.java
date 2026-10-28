package com.sicost.billingsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.validation.BillingValues;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.billingsops.BillingModels.*;

@RestController("billingOperationsController")
@RequestMapping("/api/billings")
public class BillingController {
    private final BillingService service;
    private final BillingAmountService amounts;
    private final BillingGapService gaps;
    private final BillingReceivableService receivables;
    private final BillingRevenueQuery revenue;
    private final ObjectReader reader;
    public BillingController(BillingService service, BillingAmountService amounts, BillingGapService gaps,
            BillingReceivableService receivables, BillingRevenueQuery revenue, ObjectMapper json) {
        this.service = service;
        this.amounts = amounts;
        this.gaps = gaps;
        this.receivables = receivables;
        this.revenue = revenue;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
    private <T> T input(String body, Class<T> type) {
        try {
            JsonNode node = reader.readTree(body);
            if (node == null || !node.isObject()) throw BillingValues.invalid(null);
            return reader.forType(type).readValue(node);
        } catch (java.io.IOException ex) { throw BillingValues.invalid(null); }
    }
    @GetMapping("/preview")
    public ApiResponse<Preview> previewBilling(@RequestParam long projectId,
            @RequestParam(required=false) String month, @RequestParam(required=false) Long stageId) {
        return ApiResponse.ok(amounts.preview(new PreviewRequest(projectId, month, stageId)));
    }
    @GetMapping("/summary")
    public ApiResponse<Summary> getBillingSummary(@RequestParam(required=false) Integer year) {
        return ApiResponse.ok(revenue.summary(year));
    }
    @GetMapping("/gaps")
    public ApiResponse<GapResult> listBillingGaps(@RequestParam Map<String,String> parameters) {
        return ApiResponse.ok(gaps.list(gapRequest(parameters)));
    }
    @GetMapping("/overdue")
    public ApiResponse<OverdueResult> listBillingOverdue(@RequestParam Map<String,String> parameters) {
        return ApiResponse.ok(receivables.list(overdueRequest(parameters)));
    }
    static GapRequest gapRequest(Map<String,String> p) {
        return new GapRequest(p.get("month"), p.get("judgment"), p.get("q"), integer(p,"page"), integer(p,"size"), p.get("sort"));
    }
    static OverdueRequest overdueRequest(Map<String,String> p) {
        return new OverdueRequest(integer(p,"year"), integer(p,"stage"), longValue(p,"customerId"),
            integer(p,"overDays"), p.get("q"), integer(p,"page"), integer(p,"size"), p.get("sort"));
    }
    private static Long longValue(Map<String,String> p, String key) {
        if (!p.containsKey(key)) return null;
        try { return Long.valueOf(p.get(key)); }
        catch (NumberFormatException ex) { throw BillingValues.invalid(key); }
    }
    private static Integer integer(Map<String,String> p, String key) {
        Long value = longValue(p,key);
        if (value == null) return null;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw BillingValues.invalid(key);
        return value.intValue();
    }
    @PostMapping("/{id}/confirm")
    public ApiResponse<BillingDetail> confirmBilling(@PathVariable long id) {
        return ApiResponse.ok(service.confirm(id));
    }
    @PostMapping("/{id}/cancel")
    public ApiResponse<BillingDetail> cancelBilling(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.cancel(id, input(body, Cancellation.class)));
    }
    @PostMapping("/{id}/amendments")
    public ApiResponse<BillingDetail> createBillingAmendment(@PathVariable long id, @RequestBody String body) {
        return ApiResponse.ok(service.amend(id, input(body, Amendment.class)));
    }
}
