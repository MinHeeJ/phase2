package com.sicost.billingsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiResponse;
import com.sicost.common.billing.BillingRules;
import com.sicost.common.security.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.billingsops.BillingReviewModels.*;

@RestController
public class BillingReviewController {
    private final BillingLifecycleService lifecycle;
    private final BillingPreviewService preview;
    private final BillingReviewService review;
    private final PermissionService permissions;
    private final ObjectReader reader;
    public BillingReviewController(BillingLifecycleService lifecycle,BillingPreviewService preview,
            BillingReviewService review,PermissionService permissions,ObjectMapper json) {
        this.lifecycle=lifecycle; this.preview=preview; this.review=review; this.permissions=permissions;
        this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private JsonNode input(String body) {
        if(body==null || body.isBlank()) throw BillingRules.invalid(null);
        try { return reader.readTree(body); }
        catch(JsonProcessingException e) { throw BillingRules.invalid(null); }
    }
    private void read(String operation) { BillingActions.can(permissions,operation); }
    @GetMapping("/api/billings/preview")
    public ApiResponse<Preview> previewBilling(@RequestParam Map<String,String> query) {
        read("previewBilling");
        long project=BillingReviewInput.id(query.get("projectId"),"projectId");
        Long stage=query.containsKey("stageId")?BillingReviewInput.id(query.get("stageId"),"stageId"):null;
        return ApiResponse.ok(preview.preview(new PreviewKey(project,query.get("month"),stage)));
    }
    @GetMapping("/api/billings/gaps")
    public ApiResponse<GapResult> listBillingGaps(@RequestParam Map<String,String> query) {
        read("listBillingGaps"); return ApiResponse.ok(review.gaps(query));
    }
    @GetMapping("/api/billings/overdue")
    public ApiResponse<OverdueResult> listBillingOverdue(@RequestParam Map<String,String> query) {
        read("listBillingOverdue"); return ApiResponse.ok(review.overdue(query));
    }
    @GetMapping("/api/billings/summary")
    public ApiResponse<Summary> getBillingSummary(@RequestParam Map<String,String> query) {
        read("getBillingSummary"); return ApiResponse.ok(review.summary(query));
    }
    @PostMapping("/api/billings/{id}/confirm")
    public ApiResponse<Detail> confirmBilling(@PathVariable String id) {
        return ApiResponse.ok(lifecycle.confirm(BillingReviewInput.id(id,"id")));
    }
    @PostMapping("/api/billings/{id}/cancel")
    public ApiResponse<Detail> cancelBilling(@PathVariable String id,@RequestBody(required=false) String body) {
        return ApiResponse.ok(lifecycle.cancel(BillingReviewInput.id(id,"id"),BillingReviewInput.reason(input(body))));
    }
    @PostMapping("/api/billings/{id}/amendments")
    public ApiResponse<Detail> createBillingAmendment(@PathVariable String id,@RequestBody(required=false) String body) {
        return ApiResponse.ok(lifecycle.amend(BillingReviewInput.id(id,"id"),BillingReviewInput.amendment(input(body))));
    }
}
