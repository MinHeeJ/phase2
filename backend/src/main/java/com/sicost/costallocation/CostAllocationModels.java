package com.sicost.costallocation;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.util.List;

public final class CostAllocationModels {
    private CostAllocationModels() {}

    public record Query(String month, Long projectId) {}
    public record ProjectPeriod(long projectId, String throughMonth) {}
    public record Person(long dev_id, String emp_se_cd, String duty_se_cd,
            @Sensitive Long indv_lbr_cst_amt) {}
    public record Assignment(long asgn_id, long dev_id, long prj_id, String cntrct_ty_cd,
            String prj_stts_cd, String asgn_se_cd, String asgn_stts_cd, BigDecimal mm_qty,
            Long bill_untprc_amt) {}
    public record Expense(long expns_id, Long dev_id, Long prj_id, String cntrct_ty_cd,
            String prj_stts_cd, long expns_amt) {}
    public record Turnkey(long prj_id, long dev_id, Long aloc_amt) {}

    // A project-only expense has no person in the source. Keep null, never invent a developer ID.
    public record AllocationRow(String cst_ym, Long dev_id, Long asgn_id, Long prj_id,
            String aloc_ty_cd, BigDecimal mm_qty, BigDecimal tot_mm_qty, BigDecimal aloc_rt,
            @Sensitive long lbr_cst_amt, @Sensitive long drct_expns_amt, long sls_amt,
            Long bill_untprc_amt, String mm_ovr_yn, String free_sprt_yn, String pre_cntrct_yn) {
        public long cost() { return Math.addExact(lbr_cst_amt, drct_expns_amt); }
    }
    public record Allocation(String calculation_month, String reference_month, boolean closed,
            String unit, List<String> warnings, List<AllocationRow> items, long unallocated_cost_amt) {}
    public record Sources(List<Person> people, List<Assignment> assignments,
            List<Expense> expenses, List<Turnkey> turnkey) {}
    public record Calculation(List<AllocationRow> items, List<String> warnings, long unallocated_cost_amt) {}
}
