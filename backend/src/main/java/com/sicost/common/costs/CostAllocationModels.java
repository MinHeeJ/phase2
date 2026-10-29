package com.sicost.common.costs;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.util.List;

public final class CostAllocationModels {
    private CostAllocationModels() {}
    public record Salary(long dev_id, String emp_se_cd, String duty_se_cd,
            @Sensitive Long indv_lbr_cst_amt) {}
    public record Assignment(long asgn_id, long dev_id, long prj_id, BigDecimal mm_qty,
            String cntrct_ty_cd, String prj_stts_cd, String asgn_se_cd, Long bill_untprc_amt) {}
    public record Expense(long expns_id, Long dev_id, Long prj_id, long expns_amt,
            String cntrct_ty_cd) {}
    public record Turnkey(long prj_id, long dev_id, long sls_amt) {}
    public record Allocation(Long dev_id, Long prj_id, Long asgn_id, String aloc_ty_cd,
            BigDecimal mm_qty, BigDecimal tot_mm_qty, BigDecimal aloc_rt,
            @Sensitive long lbr_cst_amt, @Sensitive long drct_expns_amt,
            @Sensitive Long sls_amt, Long bill_untprc_amt, boolean mm_ovr,
            boolean free_sprt, boolean pre_cntrct) {}
    public record ProjectExpense(long expns_id, long prj_id, String aloc_ty_cd, long expns_amt) {}
    public record Warning(Long dev_id, Long asgn_id, String code, String message) {}
    public record Unallocated(long dev_id, @Sensitive long lbr_cst_amt, @Sensitive long drct_expns_amt) {}
    public record Result(String month, boolean closed, List<Allocation> items,
            List<ProjectExpense> expenses, List<Warning> warnings, List<Unallocated> unallocated) {}
}
