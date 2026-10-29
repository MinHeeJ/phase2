package com.sicost.people;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PeopleCostModels {
    private PeopleCostModels() {}
    public record Period(String from, String through) {}
    public record PersonMonth(long dev_id, String month, String dev_nm, String affiliation,
        String emp_se_cd, String duty_se_cd, Long bsn_part_id, Long vendor_id, String job,
        String grade, String position, boolean on_leave, Long salary, Long allowance,
        Long insurance, Long retirement, Long employee_insurance, Long withholding, Long real_pay) {}
    public record AssignmentInfo(long asgn_id, long prj_id, String project, String contract_type,
        String job, LocalDate start, LocalDate end) {}
    public record Evidence(long dev_id, String month, Long prj_id, Long asgn_id, String project,
        String classification, String contract_type, String job, BigDecimal mm,
        Long price, @Sensitive long labor, @Sensitive long expenses, @Sensitive Long revenue,
        @Sensitive Long profit, boolean overlap, boolean free, boolean pre_contract) {}
    public record Row(long dev_id, String name, String affiliation, String position, String emp_type,
        String job, String grade, String projects, String period, String contract_types, BigDecimal mm,
        BigDecimal price, @Sensitive BigDecimal salary, @Sensitive BigDecimal allowance,
        @Sensitive BigDecimal insurance, @Sensitive BigDecimal retirement,
        @Sensitive BigDecimal labor, @Sensitive BigDecimal expenses, @Sensitive BigDecimal cost,
        @Sensitive BigDecimal revenue, @Sensitive BigDecimal profit, BigDecimal margin,
        boolean overlap, boolean on_leave, List<String> warnings) {}
    public record Summary(int people, BigDecimal active_people, BigDecimal paid_people,
        BigDecimal utilization, BigDecimal earning_rate, BigDecimal average_price,
        @Sensitive BigDecimal average_cost, @Sensitive BigDecimal average_profit, int loss_people,
        @Sensitive BigDecimal revenue, @Sensitive BigDecimal cost, @Sensitive BigDecimal profit) {}
    public record Contribution(String dimension, String name, int people, @Sensitive BigDecimal per_person_profit) {}
    public record People(List<Row> items, long total, int page, int size, String through,
        Summary summary, List<Contribution> contributions, List<String> warnings) {}
    public record Breakdown(long dev_id, String through, String view, List<Evidence> items, List<String> warnings) {}
    public record Prepared(List<Row> rows, Summary summary, List<Contribution> contributions,
        List<String> warnings, String through) {}
    public record DownloadAccess(String filename, String detail, Long actor) {}
}
