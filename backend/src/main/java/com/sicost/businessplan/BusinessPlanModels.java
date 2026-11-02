package com.sicost.businessplan;

import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.util.List;

public final class BusinessPlanModels {
    private BusinessPlanModels() {}
    public record Plan(int year, int month, Long partId, long target_revenue,
            @Sensitive long target_operating_profit, BigDecimal target_utilization_rate) {}
    public record Part(long id, String name, boolean active) {}
    public record Plans(List<Plan> items, List<Part> parts, List<String> closed_months, int year) {}
    public record PlanWrite(Plan input, String planMonth, Long actor) {}
    public record PlanAudit(String id, String type, String before, String after, Long actor) {}
    public record Attribution(long projectId, String month, Long partId) {}
    public record ClosedActual(int month, Long revenue, Long operating_profit) {}
    public record Actual(int month, Long revenue, @Sensitive Long operating_profit,
            BigDecimal utilization_rate, List<String> warnings) {}
    public record Metric(BigDecimal target, BigDecimal actual, BigDecimal difference, BigDecimal achievement_rate) {}
    public record ComparedMonth(int month, boolean target_present, Metric revenue,
            Metric operating_profit, Metric utilization_rate) {}
    public record Summary(Metric revenue, Metric operating_profit, Metric utilization_rate) {}
    public record ExportFile(String file_name, String content_type, String content_base64) {}
    public record Comparison(List<Plan> items, List<Actual> actuals, String warning,
            List<String> warnings, ComparisonData comparison, ExportFile export_file) {}
    public record ComparisonData(List<ComparedMonth> months, Summary cumulative, List<ComparedMonth> cumulative_months) {}
}
