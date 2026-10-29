package com.sicost.projects;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class CostProjectModels {
    private CostProjectModels() {}
    public record Query(int year, String from, String month, String type, Long partId,
        String status, String q, BillingPaging paging) {}
    public record Period(long id, String from, String month) {}
    public record Source(long prj_id, String prj_no, String prj_nm, String cntrct_ty_cd,
        String prj_stts_cd, Long bsn_part_id, String part_name, String owner,
        Long init_cntrct_amt, Long cur_cntrct_amt, @Sensitive Long trgt_cst_amt,
        BigDecimal trgt_mrgn_rt, BigDecimal plan_mm_qty, BigDecimal remaining_mm_qty,
        @Sensitive BigDecimal remaining_cost_amt, BigDecimal actl_prgs_rt, long headcount) {}
    public record PersonName(long dev_id, String dev_nm) {}
    public record Row(long prj_id, String prj_no, String prj_nm, String cntrct_ty_cd,
        String prj_stts_cd, Long bsn_part_id, String part_name, Long init_cntrct_amt,
        Long cur_cntrct_amt, @Sensitive Long trgt_cst_amt, BigDecimal trgt_mrgn_rt,
        BigDecimal plan_mm_qty, BigDecimal actual_mm_qty, Long confirmed_supply_amt,
        @Sensitive Long lbr_cst_amt, @Sensitive long drct_expns_amt, @Sensitive Long total_cost_amt,
        @Sensitive Long profit_amt, BigDecimal margin_rt, BigDecimal expected_margin_rt,
        BigDecimal monthly_margin_rt, BigDecimal budget_use_rt, BigDecimal target_diff_rt,
        BigDecimal per_mm_price_amt, @Sensitive BigDecimal per_mm_cost_amt,
        @Sensitive BigDecimal per_mm_profit_amt, BigDecimal free_mm_qty, @Sensitive Long free_cost_amt,
        @Sensitive Long rnd_cost_amt, String budget_warning, boolean low_margin,
        String main_cause, List<String> warnings) {}
    public record Month(String month, String status, Long confirmed_supply_amt,
        @Sensitive Long lbr_cst_amt, @Sensitive long drct_expns_amt, @Sensitive Long total_cost_amt,
        @Sensitive Long profit_amt, BigDecimal margin_rt, BigDecimal mm_qty,
        BigDecimal free_mm_qty, @Sensitive Long free_cost_amt, @Sensitive Long budget_remaining_amt,
        Long reference_revenue_amt) {}
    public record Person(String month, long dev_id, String dev_nm, Long asgn_id,
        BigDecimal mm_qty, @Sensitive Long lbr_cst_amt, @Sensitive long drct_expns_amt,
        Long personal_revenue_amt, String free_sprt_yn) {}
    public record Summary(long project_count, Long confirmed_supply_amt, @Sensitive Long profit_amt,
        long low_margin_count, long budget_exceeded_count, @Sensitive Long rnd_cost_amt) {}
    public record Page(String month, String status, String referenceMonth, String unit,
        List<String> warnings, List<Row> items, long total, int page, int size, Summary summary) {}
    public record Detail(String month, String status, String referenceMonth, String unit,
        List<String> warnings, Row project, List<Month> monthly_rows, List<Person> people_rows,
        Long reference_revenue_amt, Causes causes) {}
    public record Cause(String code, String label, @Sensitive Long amount, String basis,
        String owner, String action, String navigation, boolean available) {}
    public record Causes(String month, String status, String referenceMonth, String unit,
        List<String> warnings, long prj_id, int year, List<Cause> items, String main_cause,
        @Sensitive BigDecimal progress_excess_amt, @Sensitive BigDecimal unexplained_amt) {}
    public record SimulationInput(int headcount_delta, long monthly_cost_amt, int months, Long monthly_price_amt) {}
    public record Simulation(String month, String status, String referenceMonth, String unit,
        List<String> warnings, boolean simulated, @Sensitive long cost_delta_amt,
        long revenue_delta_amt, @Sensitive long profit_delta_amt) {}
    public record Access(String filename, String detail, Long actor) {}
}
