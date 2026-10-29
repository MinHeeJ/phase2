package com.sicost.people;

import com.sicost.common.costallocation.CostAllocationModels.Allocation;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class CostPeopleModels {
    private CostPeopleModels() {}
    public record Person(long dev_id, String mng_no, String dev_nm, String affiliation_name,
        Long vendor_id, Long bsn_part_id, String pstn_cd, String emp_se_cd, String duty_se_cd,
        String job_cd, String grd_cd, boolean on_leave) {}
    public record Payroll(long dev_id, @Sensitive Long slry_amt, @Sensitive Long alwnc_amt,
        @Sensitive Long sins_cmpny_amt, @Sensitive Long sins_emp_amt, @Sensitive Long whtax_amt,
        @Sensitive Long rtrpay_acml_amt, @Sensitive Long real_pay_amt,
        @Sensitive Long indv_lbr_cst_amt, @Sensitive long team_expns_amt, @Sensitive long project_expns_amt) {}
    public record Project(long asgn_id, long dev_id, long prj_id, String prj_nm, String cntrct_ty_cd,
        LocalDate bgn_dt, LocalDate end_dt) {}
    public record Row(long dev_id, String mng_no, String dev_nm, String affiliation_name,
        Long vendor_id, Long bsn_part_id, String pstn_cd, String emp_se_cd, String job_cd,
        String grd_cd, boolean on_leave, List<Project> projects, String cntrct_ty_cd,
        BigDecimal mm_qty, BigDecimal bill_untprc_amt, @Sensitive BigDecimal slry_amt,
        @Sensitive BigDecimal alwnc_amt, @Sensitive BigDecimal sins_cmpny_amt,
        @Sensitive BigDecimal sins_emp_amt, @Sensitive BigDecimal whtax_amt,
        @Sensitive BigDecimal rtrpay_acml_amt, @Sensitive BigDecimal real_pay_amt,
        @Sensitive BigDecimal indv_lbr_cst_amt, @Sensitive BigDecimal team_expns_amt,
        @Sensitive BigDecimal project_expns_amt, @Sensitive BigDecimal total_cost_amt,
        BigDecimal personal_revenue_amt, @Sensitive BigDecimal profit_amt, BigDecimal margin_rt,
        boolean overlap, List<String> warnings) {}
    public record Monthly(String month, String status, Person person, Payroll payroll,
        List<Allocation> items, @Sensitive Long unallocated_labor_amt,
        @Sensitive long unallocated_expns_amt, List<String> warnings) {}
    public record Contribution(String job_cd, String grd_cd, int people_count,
        @Sensitive BigDecimal profit_amt) {}
    public record Summary(int people_count, int average_people_count, int priced_people_count,
        int loss_people_count, BigDecimal monthly_active_count, BigDecimal monthly_paid_count,
        BigDecimal utilization_rt, BigDecimal paid_rt, BigDecimal average_price_amt,
        @Sensitive BigDecimal average_cost_amt, @Sensitive BigDecimal average_profit_amt,
        List<Contribution> contributions) {}
    public record Page(String month, String status, String referenceMonth, String unit,
        List<String> warnings, List<Row> items, long total, int page, int size, Summary summary) {}
    public record Breakdown(String month, String status, String referenceMonth, String unit,
        List<String> warnings, Row person, List<Allocation> items, List<Monthly> monthly_rows) {}
    public record Access(String name, String description, Long actor) {}
    public record File(String name, byte[] bytes) {}
}
