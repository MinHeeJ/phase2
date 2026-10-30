package com.sicost.projects;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.security.Sensitive;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class CostProjectModels {
    private CostProjectModels() {}
    public record Query(int year, String type, Long partId, String status, String q, BillingPaging paging) {}
    public record Period(long id, String fromMonth, String throughMonth) {}
    public record Source(long prj_id, String prj_no, String prj_nm, String cntrct_ty_cd,
            Long cur_cntrct_amt, Long init_cntrct_amt, Long trgt_cst_amt, BigDecimal trgt_mrgn_rt,
            LocalDate cur_end_dt, String owner_name, BigDecimal progress_rt) {}
    public record Plan(String month, BigDecimal mm_qty) {}
    public record Labor(long dev_id, String dev_nm, Long monthly_cost_amt, BigDecimal future_mm_qty) {}
    public record Reference(String month, Long amount) {}
    public record Row(long prj_id, String prj_no, String prj_nm, String cntrct_ty_cd,
            Long cur_cntrct_amt, Long trgt_cst_amt, BigDecimal trgt_mrgn_rt,
            BigDecimal plan_mm_qty, BigDecimal actual_mm_qty, Long confirmed_supply_amt,
            @Sensitive long lbr_cst_amt, long drct_expns_amt, @Sensitive long cumulative_cost_amt,
            Long profit_amt, BigDecimal margin_rt, BigDecimal budget_use_rt, Long budget_remaining_amt,
            Long estimated_cost_amt, BigDecimal estimated_margin_rt, Long reference_revenue_amt,
            BigDecimal free_mm_qty, long free_cost_amt, Long price_per_mm_amt, Long cost_per_mm_amt,
            Long profit_per_mm_amt, boolean low_margin, String budget_warning, String primary_cause,
            Long init_cntrct_amt) {}
    public record Month(String month, boolean closed, Long confirmed_supply_amt, long lbr_cst_amt,
            long drct_expns_amt, long cumulative_cost_amt, Long profit_amt, BigDecimal margin_rt,
            BigDecimal free_mm_qty, long free_cost_amt, Long budget_remaining_amt, Long reference_revenue_amt) {}
    public record Person(String dev_nm, AllocationRow allocation) {}
    public record Cause(String cause_code, long amount, String basis, String owner_name,
            String recommended_action, String target_screen, long project_id) {}
    public record Causes(List<Cause> items, Long progress_excess_amt, Long unexplained_amt, String primary_cause) {}
    public record Summary(long confirmed_supply_amt, long profit_amt, long low_margin_count,
            long budget_over_count, long rnd_cost_amt) {}
    public record ProjectList(String calculation_month, String reference_month, boolean closed,
            String unit, List<String> warnings, List<Row> items, long total, int page, int size, Summary summary) {}
    public record Detail(String calculation_month, String reference_month, boolean closed,
            String unit, List<String> warnings, Row project, List<Month> months, List<Person> people, Causes causes) {}
    public record SimulationInput(int headcount_delta, long monthly_cost_amt, int months, Long monthly_price_amt) {}
    public record Simulation(boolean simulated, long baseline_cost_amt, long additional_cost_amt,
            long simulated_cost_amt, Long additional_revenue_amt, Long simulated_revenue_amt,
            Long simulated_profit_amt, BigDecimal simulated_margin_rt, List<String> warnings) {}
    public record Download(String name, String description, Long actor) {}
}
