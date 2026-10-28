package com.sicost.us4;

import java.math.BigDecimal;
import java.util.List;

public final class PlanComparisonModels {
    private PlanComparisonModels() {}
    public record Query(int year, Long projectId, String job) {}
    public record Source(long projectId, String projectName, String job, String jobName, BigDecimal planMm, BigDecimal actualMm) {}
    public record Row(long projectId, String projectName, String job, String jobName, BigDecimal planMm, BigDecimal actualMm, BigDecimal difference, String gap) {}
    public record Totals(BigDecimal planMm, BigDecimal actualMm) {}
    public record Page(List<Row> rows, int total, int page, int size, Totals totals, BigDecimal tolerance) {}
}
