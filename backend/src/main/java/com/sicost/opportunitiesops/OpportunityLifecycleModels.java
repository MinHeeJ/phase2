package com.sicost.opportunitiesops;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sicost.projects.ProjectModels.ProjectDetail;
import com.sicost.assignments.AssignmentModels.SaveResult;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class OpportunityLifecycleModels {
    private OpportunityLifecycleModels() {}
    public record Row(long oppt_id, String oppt_no, String oppt_nm, long cust_ptnr_id,
        Long bsn_part_id, Long owner_user_id, String bsn_stg_cd, BigDecimal win_prblt_rt,
        long expc_amt, String cntrct_ty_cd, String bgn_ym, Integer mth_cnt,
        String next_action, LocalDate next_action_dt, String rmrk_desc, String oppt_stts_cd,
        Long win_prj_id, LocalDateTime created_at, LocalDateTime updated_at,
        String amount_difference_reason, @JsonIgnore String approval,
        @JsonIgnore Long revenue, @JsonIgnore Long cost, @JsonIgnore BigDecimal margin,
        @JsonIgnore BigDecimal approved_margin, @JsonIgnore LocalDateTime approved_at,
        @JsonIgnore String evidence, @JsonIgnore String validation,
        String lost_rsn_desc, String rjct_rsn_desc) {}
    public record Need(long need_id, String job_cd, String grd_cd, int psn_cnt, String bgn_ym,
        int mth_cnt, BigDecimal mth_mm_qty, Long expc_untprc_amt, Long psn_mth_cst_amt,
        Long min_untprc_amt, Long trgt_untprc_amt, String rmrk_desc) {}
    public record NeedDetail(@JsonUnwrapped Need row, List<String> warnings) {}
    public record ApprovalHistory(String action, String opinion, Long user_id,
        LocalDateTime created_at, BigDecimal expc_mrgn_rt) {}
    public record Quote(long expc_sls_amt, long expc_cst_amt, BigDecimal expc_mrgn_rt,
        long minimum_amount, long amount_difference, BigDecimal amount_difference_rate,
        String cost_status, String approval_state, Long approved_quote_amount, List<String> warnings) {}
    public record Detail(@JsonUnwrapped Row row, List<NeedDetail> needs, Quote quote,
        List<ApprovalHistory> approval_history, boolean stale_warning, List<String> warnings) {}
    public record Approval(String opinion, String zero_cost_evidence) {}
    public record Change(long id, Map<String,Object> values, Long actor) {}
    public record Audit(String table, String id, String column, String before, String after, Long actor) {}
    public record ApprovalWrite(long id, String action, String opinion, BigDecimal margin, long amount, Long actor) {}
    public record Temp(long id, long need_id, long dev_id, String bgn_ym, String end_ym,
        BigDecimal mm_qty, String job_cd, Long price) {}
    public record Conversion(long id, long assignment, Long actor) {}
    public record Link(long id, long project, Long actor) {}
    public record Plan(long project, Need need, String month, BigDecimal mm, Long cost, Long actor) {}
    public record WinResult(Detail opportunity, ProjectDetail project, List<SaveResult> converted_assignments) {}
    public record CostSource(long id, long developer, String month, BigDecimal labor,
        BigDecimal expense, String job, String grade) {}
    public record ExportQuery(String q, String status, String stage, Long customer, Long part, String from, String to) {}
    public record ExportRow(long oppt_id, String oppt_no, String oppt_nm, String customer,
        String part, String bsn_stg_cd, BigDecimal win_prblt_rt, long expc_amt,
        String cntrct_ty_cd, String bgn_ym, Integer mth_cnt, String next_action,
        LocalDate next_action_dt, String oppt_stts_cd, String approval_state,
        Long win_prj_id, String project, long needed_people, LocalDateTime last_update) {}
    public record Download(String filename, String detail, Long actor) {}
}
