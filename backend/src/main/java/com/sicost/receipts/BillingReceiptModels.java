package com.sicost.receipts;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class BillingReceiptModels {
    private BillingReceiptModels() {}
    public record Receipt(Long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record ReceiptInput(LocalDate date, Long amount, String note, boolean notePresent) {}
    public record Root(long bill_id, long prj_id, String bill_stts_cd, String amnd_yn, Long orgnl_bill_id) {}
    public record ReceiptKey(long id, long receiptId) {}
    public record ReceiptWrite(long id, Receipt row, Long actor) {}
    public record ReceiptAudit(String id, String type, String before, String after, Long actor) {}
    public record ReceiptDetail(Map<String, Object> billing, List<Map<String, Object>> details,
        List<Receipt> receipts, List<Map<String, Object>> amendments, Long original_bill_id,
        List<Map<String, Object>> change_history, List<String> warnings) {}
}
