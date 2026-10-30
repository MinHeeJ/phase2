package com.sicost.people;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.util.List;

public final class PeopleModels {
    private PeopleModels() {}
    public record Filters(String empType, Long partId, Long vendorId, String contractType, String job) {}
    public record Query(int year, String view, Filters filters, boolean lossOnly, boolean overlapOnly,
            boolean excludeTurnkeyPrice, BillingPaging paging) {}
    public record Period(String fromMonth, String throughMonth) {}
    public record PersonMonth(long dev_id, String dev_nm, Long vendor_id, String vendor_name,
            String pstn_cd, String emp_se_cd, String duty_se_cd, Long bsn_part_id, String job_cd,
            String grd_cd, String month, boolean on_leave, boolean payroll_present,
            long slry_amt, long alwnc_amt, long sins_cmpny_amt, long sins_emp_amt, long whtax_amt,
            long real_pay_amt, long rtrpay_acml_amt, long indv_lbr_cst_amt, long team_expns_amt,
            long project_expns_amt) {}
    public record ProjectLink(long asgn_id, long dev_id, long prj_id, String prj_nm,
            String cntrct_ty_cd, String job_cd, String bgn_dt, String end_dt) {}
    public record PeopleRow(long dev_id, String dev_nm, String vendor_name, String pstn_cd,
            String emp_se_cd, Long bsn_part_id, List<String> project_names, String period,
            List<String> contract_types, BigDecimal mm_qty, Long bill_untprc_amt,
            @Sensitive long slry_amt, @Sensitive long alwnc_amt, @Sensitive long sins_cmpny_amt,
            @Sensitive long sins_emp_amt, @Sensitive long whtax_amt, @Sensitive long real_pay_amt,
            @Sensitive long rtrpay_acml_amt, @Sensitive long indv_lbr_cst_amt,
            long team_expns_amt, long project_expns_amt, @Sensitive long tot_cst_amt,
            long sls_amt, @Sensitive long prft_amt, BigDecimal mrgn_rt, long idle_cst_amt,
            String mm_ovr_yn, boolean expandable) {}
    public record Contribution(String job_cd, String grd_cd, int psn_cnt, Long contribution_amt) {}
    public record Summary(int psn_cnt, BigDecimal avg_working_psn_cnt, BigDecimal avg_earning_psn_cnt,
            Long avg_price_amt, Long avg_cost_amt, Long avg_profit_amt, long loss_psn_cnt,
            BigDecimal utilization_rt, BigDecimal earning_rt, List<Contribution> contributions) {}
    public record PeopleList(String calculation_month, String reference_month, boolean closed,
            String unit, List<String> warnings, List<PeopleRow> items, long total, int page,
            int size, Summary summary) {}
    public record BreakdownRow(String cst_ym, Long asgn_id, Long prj_id, String prj_nm,
            String aloc_ty_cd, BigDecimal mm_qty, Long bill_untprc_amt, @Sensitive long lbr_cst_amt,
            long drct_expns_amt, long sls_amt, @Sensitive long prft_amt, long idle_cst_amt,
            String mm_ovr_yn) {}
    public record PersonBreakdown(String calculation_month, String reference_month, boolean closed,
            String unit, List<String> warnings, long dev_id, List<BreakdownRow> items) {}
    public record Download(String name, String description, Long actor) {}
}
