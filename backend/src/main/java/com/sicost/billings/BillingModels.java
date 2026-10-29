package com.sicost.billings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class BillingModels {
    private BillingModels() {}
    public record BillingRow(Long bill_id, Long bill_stg_id, boolean virtual_plan, String bill_no,
            long prj_id, String project_name, Long customer_id, String customer_name, String contract_type,
            String atrb_ym, LocalDate bill_dt, long sply_amt, long vat_amt, long tot_amt,
            String bill_stts_cd, String txinv_no, LocalDate txinv_dt, LocalDate rcpt_plan_dt,
            long received_amt, long adjusted_total_amt, long receivable_amt, Integer reminder_stage,
            int bill_odr_no, String amnd_yn, String rmrk_desc) {}
    public record DetailLine(Long asgn_id, Long dev_id, Long cntrct_dev_id, String display_name,
            String work_ym, BigDecimal mm_qty, BigDecimal source_mm_qty, Long untprc_amt,
            BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty,
            long ovtm_amt, long bill_amt, String rmrk_desc) {}
    public record Receipt(long receipt_id, LocalDate rcpt_dt, long rcpt_amt, String rmrk_desc) {}
    public record History(String description, LocalDateTime changed_at, String user_id) {}
    public record BillingDetail(BillingRow billing, List<DetailLine> details, List<Receipt> receipts,
            List<BillingRow> amendments, Long original_bill_id, List<History> change_history,
            List<String> warnings) {}
    public record BillingPage(List<BillingRow> items, long total, int page, int size) {}
    public record Project(long prj_id, String cntrct_ty_cd, String stlm_mthd_cd, String use_yn) {}
    public record Stage(long bill_stg_id, long prj_id, int stg_odr_no, long sply_amt,
            LocalDate bill_plan_dt) {}
    public record State(long bill_id, long prj_id, Long bill_stg_id, int bill_odr_no, String atrb_ym,
            LocalDate bill_dt, long sply_amt, long vat_amt, String bill_stts_cd, String amnd_yn,
            Long orgnl_bill_id, LocalDate rcpt_plan_dt, String txinv_no, LocalDate txinv_dt,
            String rmrk_desc, LocalDate bill_plan_dt, String cntrct_ty_cd) {}
    public record Source(long asgn_id, long dev_id, long cntrct_dev_id, String display_name,
            String asgn_se_cd, String work_ym, BigDecimal mm_qty, Long untprc_amt,
            Long atnd_id, BigDecimal work_day_qty, BigDecimal work_tm_qty, BigDecimal ovtm_tm_qty,
            BigDecimal mth_work_day_qty) {}
    public record SourceScope(long projectId, String month, LocalDate first, LocalDate last) {}
    public record StageKey(long projectId, Long stageId) {}
    public record DuplicateScope(long projectId, Long stageId, String month) {}
    public record Overtime(long asgn_id, long ovtm_amt) {}
    public record Input(Long project_id, Long stage_id, String month, boolean additional,
            Integer bill_odr_no, LocalDate bill_dt, Long sply_amt, Long vat_amt,
            LocalDate rcpt_plan_dt, String txinv_no, LocalDate txinv_dt, String rmrk_desc,
            boolean recalculate, List<Overtime> details, Set<String> present) {}
    public record Write(long id, String number, State state, Long actor) {}
    public record DetailWrite(long billId, DetailLine line) {}
    public record Audit(String id, String type, String field, String before, String after, Long actor) {}
    public record Amount(long supply, List<DetailLine> details, List<String> warnings) {}
}
