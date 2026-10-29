package com.sicost.costsops;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class RenegotiationModels {
    private RenegotiationModels() {}
    public record Query(int windowMonths, BigDecimal marginBelow, BigDecimal raiseRate,
        String empType, Long projectId, BillingPaging paging) {}
    public record Population(LocalDate today, LocalDate through, String empType, Long projectId) {}
    public record Candidate(long asgn_id, long dev_id, String dev_nm, String emp_se_cd,
        long prj_id, String prj_nm, LocalDate end_dt, Long bill_untprc_amt) {}
    public record Row(long asgn_id, long dev_id, String dev_nm, String emp_se_cd,
        long prj_id, String prj_nm, LocalDate end_dt, long days_until_end, BigDecimal mm_qty,
        Long bill_untprc_amt, @Sensitive Long lbr_cst_amt, @Sensitive Long drct_expns_amt,
        @Sensitive BigDecimal monthly_cost_amt, @Sensitive BigDecimal margin_rt, @Sensitive BigDecimal target_price_amt,
        @Sensitive BigDecimal required_raise_rt, @Sensitive BigDecimal annual_profit_increase_amt,
        @Sensitive BigDecimal target_annual_profit_increase_amt) {}
    public record Summary(long people_count, @Sensitive BigDecimal margin_rt,
        @Sensitive BigDecimal annual_profit_increase_amt,
        @Sensitive BigDecimal target_annual_profit_increase_amt) {}
    public record Result(String month, String status, String referenceMonth, String unit,
        List<String> warnings, List<Row> items, long total, int page, int size, Summary summary) {}
    public record Download(String filename, byte[] bytes) {}
    public record Access(String filename, String detail, Long actor) {}
}
