package com.sicost.common.costallocation;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.util.List;

public final class CostAllocationModels {
    private CostAllocationModels() {}

    public record Scope(String month, Long projectId) {}
    public record Person(long dev_id, String emp_se_cd, String duty_se_cd,
        @Sensitive Long indv_lbr_cst_amt) {}
    public record Assignment(long asgn_id, long dev_id, long prj_id, BigDecimal mm_qty,
        String asgn_stts_cd, String cntrct_ty_cd, String prj_stts_cd, String asgn_se_cd,
        Long bill_untprc_amt, Long tk_sls_amt) {}
    public record Expense(long expns_id, Long dev_id, Long prj_id, String cntrct_ty_cd,
        String prj_stts_cd, @Sensitive long expns_amt) {}
    public record Allocation(String cst_ym, long dev_id, Long prj_id, Long asgn_id,
        String aloc_ty_cd, BigDecimal mm_qty, BigDecimal tot_mm_qty, BigDecimal aloc_rt,
        @Sensitive Long lbr_cst_amt, @Sensitive long drct_expns_amt, Long sls_amt,
        Long bill_untprc_amt, String mm_ovr_yn, String free_sprt_yn, String pre_cntrct_yn) {}
    public record ProjectExpense(long expns_id, long prj_id, String aloc_ty_cd,
        @Sensitive long drct_expns_amt) {}
    public record Unallocated(long dev_id, @Sensitive Long lbr_cst_amt,
        @Sensitive long drct_expns_amt) {}
    public record Calculation(List<Allocation> items, List<ProjectExpense> project_expenses,
        List<Unallocated> unallocated, List<String> warnings) {}
    public record Result(String month, String status, String referenceMonth, String unit,
        List<Allocation> items, List<ProjectExpense> project_expenses,
        List<Unallocated> unallocated, List<String> warnings) {}
    public record ProjectTotal(@Sensitive long amount, List<String> missing_salary_months) {}
}
