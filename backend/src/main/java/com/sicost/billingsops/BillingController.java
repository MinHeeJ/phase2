package com.sicost.billingsops;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.billingsops.BillingModels.*;

@RestController("billingOperationsController")
public class BillingController {
    private final BillingAmountService amounts;
    private final BillingGapService gaps;
    private final BillingReceivableService receivables;
    private final BillingRevenueQuery revenue;
    private final BillingCommandService commands;
    private final ObjectReader reader;
    public BillingController(BillingAmountService amounts, BillingGapService gaps,
        BillingReceivableService receivables, BillingRevenueQuery revenue,
        BillingCommandService commands, ObjectMapper json) {
        this.amounts = amounts;
        this.gaps = gaps;
        this.receivables = receivables;
        this.revenue = revenue;
        this.commands = commands;
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    static BillingPaging paging(Map<String, String> q, BillingPaging.View view) {
        Integer page = BillingInput.integer(q, "page"), size = BillingInput.integer(q, "size");
        return new BillingPaging(page == null ? 0 : page, size == null ? 50 : size, q.get("sort"), view);
    }
    @GetMapping("/api/billings/preview")
    public ApiResponse<Preview> previewBilling(@RequestParam Map<String, String> q) {
        return ApiResponse.ok(amounts.preview(BillingInput.id(q, "projectId", true), q.get("month"), BillingInput.id(q, "stageId", false)));
    }
    @GetMapping("/api/billings/gaps")
    public ApiResponse<GapResult> listBillingGaps(@RequestParam Map<String, String> q) {
        return ApiResponse.ok(gaps.gaps(new GapFilter(q.get("month"), q.get("judgment"), q.get("q")), paging(q, BillingPaging.View.GAPS)));
    }
    @GetMapping("/api/billings/overdue")
    public ApiResponse<OverdueResult> listBillingOverdue(@RequestParam Map<String, String> q) {
        var filter = new OverdueFilter(BillingInput.integer(q, "year"), BillingInput.integer(q, "stage"),
            BillingInput.id(q, "customerId", false), BillingInput.integer(q, "overDays"), q.get("q"));
        return ApiResponse.ok(receivables.overdue(filter, paging(q, BillingPaging.View.OVERDUE)));
    }
    @GetMapping("/api/billings/summary")
    public ApiResponse<Summary> getBillingSummary(@RequestParam Map<String, String> q) {
        return ApiResponse.ok(revenue.summary(BillingInput.integer(q, "year")));
    }
    @PostMapping("/api/billings/{id}/confirm")
    public ApiResponse<BillingDetail> confirmBilling(@PathVariable long id) {
        return ApiResponse.ok(commands.confirm(id));
    }
    @PostMapping("/api/billings/{id}/cancel")
    public ApiResponse<BillingDetail> cancelBilling(@PathVariable long id, @RequestBody String body) {
        var node = BillingInput.parse(reader, body, Set.of("reason"));
        return ApiResponse.ok(commands.cancel(id, BillingInput.text(node, "reason", 500, true)));
    }
    @PostMapping("/api/billings/{id}/amendments")
    public ApiResponse<BillingDetail> createBillingAmendment(@PathVariable long id, @RequestBody String body) {
        var node = BillingInput.parse(reader, body, Set.of("sply_amt", "vat_amt", "reason", "bill_dt", "rcpt_plan_dt", "txinv_no", "txinv_dt"));
        return ApiResponse.ok(commands.amend(id, BillingInput.amendment(node)));
    }
}
