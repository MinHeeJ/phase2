package com.sicost.receipts;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class BillingReceiptModels {
    private BillingReceiptModels() {}
    public record ReceiptInput(LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record ReceiptRow(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record ReceiptKey(long id, long receiptId) {}
    public record ReceiptWrite(long id, Long receiptId, ReceiptInput input, Long actor) {}
    public record ReceiptBillingRow(Long bill_id, Long bill_stg_id, boolean virtual_plan,
        String bill_no, long prj_id, String project_name, Long customer_id, String customer_name,
        String contract_type, String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt,
        long tot_amt, String bill_stts_cd, String txinv_no, LocalDate txinv_dt,
        LocalDate rcpt_plan_dt, long received_amt, long adjusted_total_amt, long receivable_amt,
        Integer reminder_stage) {}
    public record ReceiptDetailLine(Long asgn_id, Long dev_id, Long cntrct_dev_id,
        String display_name, String work_ym, BigDecimal mm_qty, BigDecimal source_mm_qty,
        Long untprc_amt, BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty,
        long ovtm_amt, long bill_amt, String rmrk_desc) {}
    public record ReceiptHistory(String description, LocalDateTime changed_at, String user_id) {}
    public record ReceiptBillingDetail(ReceiptBillingRow billing, List<ReceiptDetailLine> details,
        List<ReceiptRow> receipts, List<ReceiptBillingRow> amendments, Long original_bill_id,
        List<ReceiptHistory> change_history, List<String> warnings) {}
}
