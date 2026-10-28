package com.sicost.billingsops;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class BillingModels {
    private BillingModels() {}
    public record Bill(long bill_id, String bill_no, long prj_id, Long bill_stg_id,
        int bill_odr_no, String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt,
        long tot_amt, String bill_stts_cd, String txinv_no, LocalDate txinv_dt,
        String amnd_yn, Long orgnl_bill_id, LocalDate rcpt_plan_dt, String rmrk_desc,
        String contract_type, String project_name, Long customer_id, String customer_name) {}
    public record BillingRow(Long bill_id, Long bill_stg_id, boolean virtual_plan, String bill_no,
        long prj_id, String project_name, Long customer_id, String customer_name,
        String contract_type, String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt,
        long tot_amt, String bill_stts_cd, String txinv_no, LocalDate txinv_dt,
        LocalDate rcpt_plan_dt, long received_amt, long adjusted_total_amt, long receivable_amt,
        Integer reminder_stage) {}
    public record DetailLine(Long asgn_id, Long dev_id, Long cntrct_dev_id, String display_name,
        String work_ym, BigDecimal mm_qty, BigDecimal source_mm_qty, Long untprc_amt,
        BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty, long ovtm_amt,
        long bill_amt, String rmrk_desc) {}
    public record Receipt(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record ChangeHistory(String description, LocalDateTime changed_at, String user_id) {}
    public record BillingDetail(BillingRow billing, List<DetailLine> details, List<Receipt> receipts,
        List<BillingRow> amendments, Long original_bill_id, List<ChangeHistory> change_history,
        List<String> warnings) {}
    public record Amendment(BigDecimal sply_amt, BigDecimal vat_amt, String reason,
        LocalDate bill_dt, LocalDate rcpt_plan_dt, String txinv_no, LocalDate txinv_dt) {}
    public record Cancellation(String reason) {}
    public record StatusWrite(long id, String before, String after, String reason, Long actor) {}
    public record AmendmentWrite(String number, Bill original, Amendment input, Long actor) {}
    public record AmendmentDetail(long id, String month, long amount, String reason) {}
    public record Audit(String id, String type, String before, String after, String reason, Long actor) {}
    public record Project(long project_id, String project_name, String contract_type,
        String settlement, String use_yn) {}
    public record Stage(long stage_id, long project_id, long sply_amt, LocalDate bill_plan_dt) {}
    public record SourceKey(long projectId, String month) {}
    public record Source(long asgn_id, long dev_id, Long cntrct_dev_id, String display_name,
        String management_number, String kind, LocalDate bgn_dt, BigDecimal mm_qty,
        Long price, BigDecimal days, BigDecimal hours, BigDecimal overtime,
        BigDecimal denominator, String settlement) {}
    public record Preview(long project_id, Long bill_stg_id, String month, long sply_amt,
        long suggested_vat_amt, LocalDate rcpt_plan_dt, List<DetailLine> details,
        List<String> warnings, List<Long> missing_price_assignments) {}
    public record PreviewRequest(long projectId, String month, Long stageId) {}
    public record GapRequest(String month, String judgment, String q, Integer page,
        Integer size, String sort) {}
    public record SuspectedPerson(String name, String management_number) {}
    public record GapRow(long project_id, String project_name, String month,
        long basis_supply_amt, long confirmed_supply_amt, long difference_amt,
        BigDecimal tolerance, String judgment, String explanation, boolean attendance_missing,
        List<SuspectedPerson> suspected_people, List<Long> billing_ids) {}
    public record GapResult(String month, long total_difference_amt, List<GapRow> items,
        long total, int page, int size) {}
    public record OverdueRequest(Integer year, Integer stage, Long customerId, Integer overDays,
        String q, Integer page, Integer size, String sort) {}
    public record Part(String sales_part, String hq_yn) {}
    public record PartKey(long projectId, String month) {}
    public record Sent(LocalDateTime sent_at) {}
    public record OverdueRow(BillingRow billing, Integer overdue_days, Integer stage,
        String due_status, List<String> recipient_roles, LocalDate next_notification_date,
        List<Sent> sent_history, String sales_part) {}
    public record CustomerTotal(Long customer_id, String customer_name, long receivable_amt) {}
    public record StageCount(int stage, long count) {}
    public record OverdueResult(List<OverdueRow> items, List<CustomerTotal> customer_totals,
        List<StageCount> stage_counts, long total, int page, int size) {}
    public record Revenue(String month, long confirmed_supply_amt) {}
    public record Cash(LocalDate rcpt_dt, long rcpt_amt) {}
    public record Summary(int year, long annual_confirmed_supply_amt,
        long current_month_confirmed_supply_amt, long confirmed_receivable_amt, long overdue_amt,
        long cash_receipts_vat_included_amt, long planned_supply_amt, long draft_supply_amt,
        List<Revenue> monthly_revenue, List<Cash> receipts_by_date) {}
    public record Balance(long received, long amendment_supply, long amendment_total,
        long receipt_count, long amendment_count) {}
}
