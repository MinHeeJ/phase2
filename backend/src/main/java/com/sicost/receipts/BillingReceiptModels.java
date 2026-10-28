package com.sicost.receipts;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

public final class BillingReceiptModels {
    private BillingReceiptModels() {}
    public record ReceiptInput(LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record ReceiptRow(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record BillState(long bill_id, String bill_stts_cd, String amnd_yn, Long orgnl_bill_id, long tot_amt) {}
    public record ReceiptKey(long id, long receiptId) {}
    public record ReceiptWrite(long id, Long receiptId, ReceiptInput input, Long actor) {}
    public record ReceiptAudit(String id, String type, String before, String after, String reason, Long actor) {}
    public record ReceiptDetail(JsonNode billing, List<JsonNode> details, List<ReceiptRow> receipts,
        List<JsonNode> amendments, Long original_bill_id, List<JsonNode> change_history, List<String> warnings) {}
}
