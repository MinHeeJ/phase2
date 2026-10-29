package com.sicost.billingsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.validation.BillingValues;
import java.time.Clock;
import java.util.Map;
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
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public BillingController(BillingAmountService amounts,BillingGapService gaps,
            BillingReceivableService receivables,BillingRevenueQuery revenue,
            BillingCommandService commands,ObjectMapper json,Clock clock) {
        this.amounts=amounts;this.gaps=gaps;this.receivables=receivables;this.revenue=revenue;
        this.commands=commands;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);this.clock=clock;
    }
    private JsonNode body(String body) {
        try{return reader.readTree(body);}catch(JsonProcessingException ex){throw BillingValues.invalid(null);}
    }
    @GetMapping("/api/billings/preview")
    public ApiResponse<Preview> previewBilling(@RequestParam Map<String,String> q) {
        return ApiResponse.ok(amounts.preview(new Scope(BillingInput.identifier(q,"projectId",true),q.get("month"),BillingInput.identifier(q,"stageId",false))));
    }
    @GetMapping("/api/billings/gaps")
    public ApiResponse<Gaps> listBillingGaps(@RequestParam Map<String,String> q) {
        return ApiResponse.ok(gaps.list(BillingInput.gaps(q)));
    }
    @GetMapping("/api/billings/overdue")
    public ApiResponse<Overdues> listBillingOverdue(@RequestParam Map<String,String> q) {
        return ApiResponse.ok(receivables.list(BillingInput.overdue(q,clock)));
    }
    @GetMapping("/api/billings/summary")
    public ApiResponse<Summary> getBillingSummary(@RequestParam Map<String,String> q) {
        return ApiResponse.ok(revenue.summary(BillingInput.integer(q,"year")));
    }
    @PostMapping("/api/billings/{id}/confirm")
    public ApiResponse<Detail> confirmBilling(@PathVariable long id) {
        return ApiResponse.ok(commands.confirm(id));
    }
    @PostMapping("/api/billings/{id}/cancel")
    public ApiResponse<Detail> cancelBilling(@PathVariable long id,@RequestBody String body) {
        return ApiResponse.ok(commands.cancel(id,BillingInput.cancel(body(body))));
    }
    @PostMapping("/api/billings/{id}/amendments")
    public ApiResponse<Detail> createBillingAmendment(@PathVariable long id,@RequestBody String body) {
        return ApiResponse.ok(commands.amend(id,BillingInput.amendment(body(body))));
    }
}
