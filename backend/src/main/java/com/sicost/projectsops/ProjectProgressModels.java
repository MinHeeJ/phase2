package com.sicost.projectsops;

import com.sicost.common.security.Sensitive;
import com.sicost.apiops.ProjectBoardModels.Phase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProjectProgressModels {
    private ProjectProgressModels() {}
    public record ProjectState(long prj_id, String cntrct_ty_cd, String prj_stts_cd, String use_yn) {}
    public record MemoSource(long memo_id, long prj_id, LocalDate wrt_dt, BigDecimal actl_prgs_rt,
        String cmpl_work_desc, String plan_work_desc, String rqst_desc) {}
    public record WeeklyMemo(long memo_id, long prj_id, LocalDate wrt_dt, Integer actl_prgs_rt,
        String cmpl_work_desc, String plan_work_desc, String rqst_desc) {}
    public record Progress(long prj_id, String pmName, Phase currentPhase,
        BigDecimal plan_prgs_rt, BigDecimal actl_prgs_rt, BigDecimal schedule_diff_rt,
        BigDecimal cost_use_rt, BigDecimal cost_progress_diff_rt,
        @Sensitive Long cumulative_cost_amt, @Sensitive Long budget_amt,
        String light, List<String> reasons, Integer memo_elapsed_days, LocalDate last_memo_dt,
        List<Phase> phases, List<WeeklyMemo> memos, List<String> missing_salary_months) {}
    public record ProjectIssue(long prj_id, String ttl_nm, String svrt_cd, String chrg_nm,
        LocalDate ddln_dt, String issue_stts_cd, String actn_desc, long issue_id,
        LocalDate reg_dt, LocalDate end_dt) {}
    public record ProjectIssues(List<ProjectIssue> items, int open_count) {}
    public record ProgressRead(long id, LocalDate today) {}
}
