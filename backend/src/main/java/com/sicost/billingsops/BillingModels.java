package com.sicost.billingsops;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import com.sicost.common.api.BillingPaging;

public final class BillingModels {
    private BillingModels() {}
    public record Project(long project_id, String project_name, String contract_type, String basis,
            String use_yn) {}
    public record Scope(long projectId, String month, Long stageId) {}
    public record Stage(long bill_stg_id, long project_id, long sply_amt, LocalDate bill_plan_dt) {}
    public record Source(long asgn_id, long dev_id, long cntrct_dev_id, String display_name,
            String management_number, String kind, LocalDate start_date, BigDecimal source_mm_qty,
            Long price, Long atnd_id, BigDecimal work_day_qty, BigDecimal work_tm_qty,
            BigDecimal ovtm_tm_qty, BigDecimal mth_work_day_qty) {}
    public record Line(Long asgn_id, Long dev_id, Long cntrct_dev_id, String display_name,
            String work_ym, BigDecimal mm_qty, BigDecimal source_mm_qty, Long untprc_amt,
            BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty,
            long ovtm_amt, long bill_amt, String rmrk_desc) {}
    public record Preview(long project_id, Long bill_stg_id, String month, long sply_amt,
            long suggested_vat_amt, LocalDate rcpt_plan_dt, List<Line> details,
            List<String> warnings, List<Long> missing_price_assignments) {}
    public record State(long bill_id, String bill_no, long prj_id, Long bill_stg_id,
            String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt, long tot_amt,
            String bill_stts_cd, String txinv_no, LocalDate txinv_dt, LocalDate rcpt_plan_dt,
            String amnd_yn, Long orgnl_bill_id, String rmrk_desc, LocalDate stage_plan_dt,
            String project_name, Long customer_id, String customer_name, String contract_type,
            String sales_part, String hq_yn, long received_amt, long adjusted_total_amt) {}
    public record Row(Long bill_id, Long bill_stg_id, boolean virtual_plan, String bill_no,
            long prj_id, String project_name, Long customer_id, String customer_name,
            String contract_type, String atrb_ym, LocalDate bill_dt, long sply_amt,
            long vat_amt, long tot_amt, String bill_stts_cd, String txinv_no,
            LocalDate txinv_dt, LocalDate rcpt_plan_dt, long received_amt,
            long adjusted_total_amt, long receivable_amt, Integer reminder_stage) {}
    public record Receipt(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record History(String description, LocalDateTime changed_at, String user_id) {}
    public record Detail(Row billing, List<Line> details, List<Receipt> receipts,
            List<Row> amendments, Long original_bill_id, List<History> change_history,
            List<String> warnings) {}
    public record Amendment(long sply_amt, long vat_amt, String reason, LocalDate bill_dt,
            LocalDate rcpt_plan_dt, String txinv_no, LocalDate txinv_dt) {}
    public record AmendmentWrite(State original, String number, Amendment input, Long actor) {}
    public record Transition(long id, String before, String after, String reason, Long actor) {}
    public record Audit(String id, String type, String before, String after, String reason, Long actor) {}
    public record Balance(long supply, long total, long received) {}
    public record Confirmed(long bill_id, long sply_amt) {}
    public record Person(String name, String management_number) {}
    public record Gap(long project_id, String project_name, String month, long basis_supply_amt,
            long confirmed_supply_amt, long difference_amt, BigDecimal tolerance, String judgment,
            String explanation, boolean attendance_missing, List<Person> suspected_people,
            List<Long> billing_ids) {}
    public record Gaps(String month, long total_difference_amt, List<Gap> items, long total,
            int page, int size) {}
    public record GapQuery(String month, String judgment, String q, BillingPaging paging) {}
    public record OverdueQuery(int year, Integer stage, Long customerId, Integer overDays,
            String q, BillingPaging paging) {}
    public record Sent(LocalDateTime sent_at) {}
    public record Overdue(Row billing, Integer overdue_days, Integer stage, String due_status,
            List<String> recipient_roles, LocalDate next_notification_date,
            List<Sent> sent_history, String sales_part) {}
    public record CustomerTotal(Long customer_id, String customer_name, long receivable_amt) {}
    public record StageCount(int stage, long count) {}
    public record Overdues(List<Overdue> items, List<CustomerTotal> customer_totals,
            List<StageCount> stage_counts, long total, int page, int size) {}
    public record Revenue(String month, long confirmed_supply_amt) {}
    public record ProjectRevenue(long prj_id, String month, long confirmed_supply_amt) {}
    public record RevenuePeriod(long projectId, String from, String through) {}
    public record Cash(LocalDate rcpt_dt, long rcpt_amt) {}
    public record Summary(int year, long annual_confirmed_supply_amt,
            long current_month_confirmed_supply_amt, long confirmed_receivable_amt,
            long overdue_amt, long cash_receipts_vat_included_amt, long planned_supply_amt,
            long draft_supply_amt, List<Revenue> monthly_revenue, List<Cash> receipts_by_date) {}
}
