package com.sicost.costsops;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class RenegotiationModels {
    private RenegotiationModels() {}
    public record Query(int windowMonths, BigDecimal marginBelow, BigDecimal raiseRate,
        String empType, Long projectId, int page, int size, String sort) {}
    public record CandidateQuery(LocalDate today, LocalDate through, String month,
        String empType, Long projectId) {}
    public record Candidate(long asgn_id, long dev_id, String dev_nm, String emp_se_cd,
        long prj_id, String prj_nm, String cntrct_ty_cd, LocalDate end_dt) {}
    public record Row(long asgn_id, long dev_id, String dev_nm, String emp_se_cd,
        long prj_id, String prj_nm, String cntrct_ty_cd, LocalDate end_dt, long days_remaining,
        BigDecimal mm_qty, Long bill_untprc_amt, @Sensitive Long allocated_cost_amt,
        BigDecimal margin_rt, BigDecimal target_untprc_amt, BigDecimal required_raise_rt,
        @Sensitive BigDecimal annual_increase_amt, @Sensitive BigDecimal target_annual_increase_amt,
        List<String> warnings) {}
    public record Summary(long people_count, BigDecimal weighted_margin_rt,
        @Sensitive BigDecimal annual_increase_amt, @Sensitive BigDecimal target_annual_increase_amt) {}
    public record Data(String month, boolean closed, List<Row> items, long total, int page,
        int size, Summary summary, List<String> warnings) {}
    public record DownloadAccess(String filename, String detail, Long actor) {}
}
