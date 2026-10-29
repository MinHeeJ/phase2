package com.sicost.apiops;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProjectBoardModels {
    private ProjectBoardModels() {}
    public record OperatingParam(String oprt_std_id, String aply_bgn_ym, String oprt_std_nm,
        BigDecimal num_val, String txt_val, String unit_nm, String use_plc_desc) {
        public BigDecimal numeric() { return num_val != null ? num_val : new BigDecimal(txt_val); }
    }
    public record BoardSource(long prj_id, String prj_no, String prj_nm, String custName,
        String cntrct_ty_cd, Long bsn_part_id, String pmName, Long cur_cntrct_amt, Long trgt_cst_amt,
        LocalDate prfm_bgn_dt, LocalDate last_memo_dt, BigDecimal actl_prgs_rt,
        int open_issue_count, int high_open_issue_count, int overdue_issue_count, int high_overdue_count) {}
    public record Phase(long phs_id, long prj_id, int phs_seq, String phs_nm, BigDecimal wght_rt,
        LocalDate plan_bgn_dt, LocalDate plan_end_dt, LocalDate cmpl_dt, String status) {}
    public record BoardRow(long prj_id, String pmName, Phase currentPhase,
        BigDecimal plan_prgs_rt, BigDecimal actl_prgs_rt, BigDecimal schedule_diff_rt,
        BigDecimal cost_use_rt, BigDecimal cost_progress_diff_rt,
        @Sensitive Long cumulative_cost_amt, @Sensitive Long budget_amt,
        String light, List<String> reasons, Integer memo_elapsed_days, LocalDate last_memo_dt,
        String prj_no, String prj_nm, String custName, String cntrct_ty_cd,
        int open_issue_count, int overdue_issue_count) {}
    public record BoardSummary(int project_count, int normal_count, int warn_count, int risk_count,
        int open_issue_count, int high_open_issue_count, int overdue_issue_count, int stale_project_count) {}
    public record StaleProject(long prj_id, String prj_nm, int memo_elapsed_days) {}
    public record BoardData(BoardSummary summary, List<BoardRow> items, int total, int page,
        int size, List<StaleProject> stale_projects) {}
    public record CostQuery(long id, String month) {}
    public record CostMonth(String month, BigDecimal amount, boolean missing_salary) {}
    public record ProjectCost(@Sensitive long cumulative_cost_amt, List<String> missing_salary_months) {}
}
