package com.sicost.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class BillingReceiptModels {
    private BillingReceiptModels() {}
    public record ReceiptInput(Long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record Receipt(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record ReceiptKey(long billId, long receiptId) {}
    public record ReceiptWrite(long billId, long receiptId, ReceiptInput input, Long actor) {}
    public record ReceiptAudit(String billId, String receiptId, String type, String before, String after, Long actor) {}
    public record BillState(long bill_id, String bill_stts_cd, String amnd_yn, Long orgnl_bill_id) {}
    public record Totals(BigDecimal adjusted_total_amt, BigDecimal received_amt) {}
    public record Balance(long adjusted_total_amt, long received_amt, long receivable_amt) {}
    public record Header(long bill_id, Long bill_stg_id, String bill_no, long prj_id,
            String project_name, Long customer_id, String customer_name, String contract_type,
            String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt, long tot_amt,
            String bill_stts_cd, String txinv_no, LocalDate txinv_dt, LocalDate rcpt_plan_dt,
            String amnd_yn, Long orgnl_bill_id) {}
    public record BillingRow(long bill_id, Long bill_stg_id, boolean virtual_plan, String bill_no,
            long prj_id, String project_name, Long customer_id, String customer_name, String contract_type,
            String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt, long tot_amt,
            String bill_stts_cd, String txinv_no, LocalDate txinv_dt, LocalDate rcpt_plan_dt,
            long received_amt, long adjusted_total_amt, long receivable_amt, Integer reminder_stage) {}
    public record DetailLine(Long asgn_id, Long dev_id, Long cntrct_dev_id, String display_name,
            String work_ym, BigDecimal mm_qty, BigDecimal source_mm_qty, Long untprc_amt,
            BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty,
            long ovtm_amt, long bill_amt, String rmrk_desc) {}
    public record Change(String description, LocalDateTime changed_at, String user_id) {}
    public record BillingDetail(BillingRow billing, List<DetailLine> details, List<Receipt> receipts,
            List<BillingRow> amendments, Long original_bill_id, List<Change> change_history,
            List<String> warnings) {}
}
