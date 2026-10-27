package com.sicost.projectboardops;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class ProjectBoardExportModels {
    private ProjectBoardExportModels() {}
    public record BoardExportQuery(LocalDate today, String month, String light, String type, Long part) {}
    public record BoardExportSource(long prj_id, String prj_nm, String custName, String cntrct_ty_cd,
        String pmName, String currentPhase, int phaseCount, BigDecimal plan_prgs_rt,
        BigDecimal memoProgress, LocalDate last_memo_dt, LocalDate prfm_bgn_dt,
        Long cur_cntrct_amt, long open_issue_count, long overdue_issue_count, long highOverdueCount) {}
    public record BoardExportRow(long prj_id, String light, String prj_nm, String custName,
        String cntrct_ty_cd, String pmName, String currentPhase, BigDecimal plan_prgs_rt,
        BigDecimal actl_prgs_rt, BigDecimal cost_use_rt, long open_issue_count,
        long overdue_issue_count, LocalDate last_memo_dt,
        @Sensitive Long cumulative_cost_amt, @Sensitive BigDecimal budget_amt) {}
    public record BoardExportParam(String key, BigDecimal number, String text) {}
    public record BoardExportAccess(String filename, String detail, Long actor) {}
}
