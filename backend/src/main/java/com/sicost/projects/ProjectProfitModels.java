package com.sicost.projects;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProjectProfitModels {
    private ProjectProfitModels() {}
    public record Source(long prj_id, String prj_no, String prj_nm, String cntrct_ty_cd,
        String prj_stts_cd, Long bsn_part_id, String part_name, String pm_name,
        Long init_cntrct_amt, Long cur_cntrct_amt, Long trgt_cst_amt, BigDecimal trgt_mrgn_rt,
        BigDecimal actl_prgs_rt, LocalDate bgn_dt, LocalDate end_dt) {}
    public record Filter(int year, String type, Long partId, String status, String q) {}
    public record Period(long id, String from, String through) {}
    public record Plan(String month, BigDecimal mm_qty) {}
    public record Forecast(String month, Long dev_id, BigDecimal mm_qty, Long monthly_cost) {}
    public record Person(long dev_id, String dev_nm) {}
    public record PersonMonth(String month, Long dev_id, String dev_nm, Long asgn_id,
        BigDecimal mm_qty, @Sensitive long lbr_cst_amt, @Sensitive long drct_expns_amt,
        @Sensitive Long reference_revenue, boolean free_sprt, boolean pre_cntrct) {}
    public record Month(String month, Long revenue, @Sensitive long labor, long expenses,
        long cost, BigDecimal mm, BigDecimal free_mm, long free_cost, BigDecimal margin,
        Long budget_remaining, Long reference_revenue, boolean closed, List<String> warnings) {}
    public record Cause(String code, String label, BigDecimal amount, String basis,
        String owner, String recommendation, String route, String unavailable) {}
    public record Causes(long prj_id, int year, List<Cause> items, String primary_cause,
        BigDecimal progress_excess, BigDecimal residual, List<String> warnings) {}
    public record Row(Source project, BigDecimal plan_mm, BigDecimal actual_mm,
        Long revenue, long cost, Long profit, BigDecimal margin, Long forecast_cost,
        BigDecimal estimated_margin, BigDecimal target_margin, BigDecimal target_difference,
        BigDecimal budget_rate, String budget_alert, boolean low_margin,
        BigDecimal revenue_per_mm, BigDecimal cost_per_mm, BigDecimal profit_per_mm,
        BigDecimal free_mm, long free_cost, long rnd_cost, String primary_cause,
        List<String> warnings) {}
    public record Detail(Row summary, String through, List<Month> months,
        List<PersonMonth> people, Causes causes, String period_note) {}
    public record Summary(long revenue, long profit, int low_margin_count,
        int budget_risk_count, long rnd_cost) {}
    public record Page(List<Row> items, long total, int page, int size, Summary summary, String through) {}
    public record Scenario(int people_change, BigDecimal monthly_cost, int months, BigDecimal monthly_price) {}
    public record Simulation(boolean simulated, BigDecimal cost_change, BigDecimal revenue_change,
        BigDecimal profit_change) {}
    public record Download(String filename, String detail, Long actor) {}
}
