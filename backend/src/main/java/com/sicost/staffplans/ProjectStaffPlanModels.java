package com.sicost.staffplans;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProjectStaffPlanModels {
    private ProjectStaffPlanModels() {}
    public record StaffPlanInput(String plan_ym, String job_cd, String grd_cd,
            BigDecimal need_mm_qty, Long expc_mth_cst_amt) {}
    public record StaffPlan(String plan_ym, String job_cd, String grd_cd,
            BigDecimal need_mm_qty, Long expc_mth_cst_amt, BigDecimal need_psn_cnt, Long src_need_id) {}
    public record ActualMM(String job_cd, BigDecimal mm_qty) {}
    public record StaffPlans(List<StaffPlan> items, List<ActualMM> actual) {}
    public record StaffProject(String use_yn, LocalDate start, LocalDate end) {}
    public record StaffCode(String group, String code) {}
    public record StaffWrite(long id, StaffPlanInput input, BigDecimal count, Long actor) {}
    public record StaffHistory(String id, String before, String after, Long actor) {}
}
