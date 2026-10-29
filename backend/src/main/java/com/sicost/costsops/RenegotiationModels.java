package com.sicost.costsops;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class RenegotiationModels {
    private RenegotiationModels() {}

    public record Filters(int windowMonths, BigDecimal marginBelow, BigDecimal raiseRate,
            String empType, Long projectId) {}
    public record Query(Filters filters, BillingPaging paging) {}
    public record SourceQuery(LocalDate today, LocalDate throughDate, String month,
            String empType, Long projectId) {}
    public record Source(long asgn_id, long dev_id, String dev_nm, String emp_se_cd,
            long prj_id, String prj_nm, LocalDate end_dt) {}
    public record Row(long asgn_id, long dev_id, String dev_nm, String emp_se_cd,
            long prj_id, String prj_nm, LocalDate end_dt, long days_remaining,
            BigDecimal mm_qty, Long bill_untprc_amt, @Sensitive long allocated_cost_amt,
            @Sensitive BigDecimal margin_rt, Long target_price_amt, BigDecimal required_raise_rt,
            @Sensitive Long assumed_annual_profit_increase_amt,
            @Sensitive Long target_annual_profit_increase_amt) {}
    public record Summary(long psn_cnt, BigDecimal weighted_margin_rt,
            Long assumed_annual_profit_increase_amt, Long target_annual_profit_increase_amt) {}
    public record Result(String calculation_month, String reference_month, boolean closed,
            String unit, List<String> warnings, List<Row> items, long total, int page, int size,
            Summary summary, BigDecimal target_margin_rate, BigDecimal raise_rate) {}
    public record Download(String name, String description, Long actor) {}
}
