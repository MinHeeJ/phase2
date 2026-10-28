package com.sicost.billingsops;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class BillingModels {
    private BillingModels() {}
    public record Project(long prj_id, String project_name, Long customer_id, String customer_name,
        String contract_type, String settlement, String use_yn) {}
    public record Bill(long bill_id, String bill_no, long prj_id, Long bill_stg_id, String atrb_ym,
        LocalDate bill_dt, long sply_amt, long vat_amt, long tot_amt, String bill_stts_cd,
        String txinv_no, LocalDate txinv_dt, String amnd_yn, Long orgnl_bill_id,
        LocalDate rcpt_plan_dt, String dun_stop_yn, String rmrk_desc) {}
    public record BillingRow(long bill_id, Long bill_stg_id, boolean virtual_plan, String bill_no,
        long prj_id, String project_name, Long customer_id, String customer_name, String contract_type,
        String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt, long tot_amt,
        String bill_stts_cd, String txinv_no, LocalDate txinv_dt, LocalDate rcpt_plan_dt,
        long received_amt, long adjusted_total_amt, long receivable_amt, Integer reminder_stage) {}
    public record DetailLine(Long asgn_id, Long dev_id, Long cntrct_dev_id, String display_name,
        String work_ym, BigDecimal mm_qty, BigDecimal source_mm_qty, Long untprc_amt,
        BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty,
        long ovtm_amt, long bill_amt, String rmrk_desc) {}
    public record Receipt(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record History(String description, LocalDateTime changed_at, String user_id) {}
    public record BillingDetail(BillingRow billing, List<DetailLine> details, List<Receipt> receipts,
        List<BillingRow> amendments, Long original_bill_id, List<History> change_history,
        List<String> warnings) {}
    public record Preview(long project_id, Long bill_stg_id, String month, long sply_amt,
        long suggested_vat_amt, LocalDate rcpt_plan_dt, List<DetailLine> details,
        List<String> warnings, List<Long> missing_price_assignments) {}
    public record Source(long asgn_id, long dev_id, long cntrct_dev_id, String display_name,
        String management_number, String kind, LocalDate bgn_dt, BigDecimal mm_qty, Long price,
        BigDecimal work_day_qty, BigDecimal rglr_tm_qty, BigDecimal ovtm_tm_qty,
        BigDecimal mth_work_day_qty, Long atnd_id) {}
    public record SourceKey(long projectId, String month, LocalDate first, LocalDate last) {}
    public record StageKey(long projectId, long stageId) {}
    public record Stage(long bill_stg_id, long sply_amt, LocalDate bill_plan_dt) {}
    public record Amendment(long sply_amt, long vat_amt, String reason, LocalDate bill_dt,
        LocalDate rcpt_plan_dt, String txinv_no, LocalDate txinv_dt) {}
    public record AmendmentWrite(long rootId, String number, Amendment input, Long actor) {}
    public record StateWrite(long id, String before, String after, String reason, Long actor) {}
    public record Audit(String id, String type, String before, String after, Long actor) {}
    public record Sent(LocalDateTime sent_at) {}
    public record Part(String sales_part, String hq_yn) {}
    public record OverdueRow(BillingRow billing, Long overdue_days, Integer stage, String due_status,
        List<String> recipient_roles, LocalDate next_notification_date, List<Sent> sent_history,
        String sales_part) {}
    public record CustomerTotal(Long customer_id, String customer_name, long receivable_amt) {}
    public record StageCount(int stage, long count) {}
    public record OverdueResult(List<OverdueRow> items, List<CustomerTotal> customer_totals,
        List<StageCount> stage_counts, long total, int page, int size) {}
    public record GapRow(long project_id, String project_name, String month, long basis_supply_amt,
        long confirmed_supply_amt, long difference_amt, BigDecimal tolerance, String judgment,
        String explanation, boolean attendance_missing, List<Person> suspected_people, List<Long> billing_ids) {}
    public record Person(String name, String management_number) {}
    public record GapResult(String month, long total_difference_amt, List<GapRow> items,
        long total, int page, int size) {}
    public record RevenueMonth(String month, long confirmed_supply_amt) {}
    public record CashDate(LocalDate rcpt_dt, long rcpt_amt) {}
    public record Summary(int year, long annual_confirmed_supply_amt, long current_month_confirmed_supply_amt,
        long confirmed_receivable_amt, long overdue_amt, long cash_receipts_vat_included_amt,
        long planned_supply_amt, long draft_supply_amt, List<RevenueMonth> monthly_revenue,
        List<CashDate> receipts_by_date) {}
    public record OverdueFilter(Integer year, Integer stage, Long customerId, Integer overDays, String q) {}
    public record GapFilter(String month, String judgment, String q) {}
}
