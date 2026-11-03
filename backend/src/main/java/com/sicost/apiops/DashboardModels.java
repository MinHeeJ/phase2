package com.sicost.apiops;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sicost.common.security.Sensitive;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class DashboardModels {
    private DashboardModels() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CardError(String code,String message) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CardState(String state,String message,CardError error) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Warning(String section,String code,String message,String basisMonth) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Comparison(Long previousAmount,BigDecimal changePercent,boolean isReferenceRate,String label) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RevenueKpi(@JsonUnwrapped CardState card,String basisMonth,@Sensitive Long amount,
        Comparison previous,@Sensitive Long targetAmount,BigDecimal achievementPercent) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProfitKpi(@JsonUnwrapped CardState card,String basisMonth,@Sensitive Long amount,
        @Sensitive BigDecimal marginPercent,Comparison previous) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record YtdProfitKpi(@JsonUnwrapped CardState card,String fromMonth,String toMonth,@Sensitive Long amount,
        @Sensitive Long targetAmount,BigDecimal achievementPercent,List<String> closedMonths,List<String> openMonths) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UtilizationKpi(@JsonUnwrapped CardState card,String basisMonth,BigDecimal confirmedMm,
        BigDecimal availableMm,BigDecimal utilizationPercent,BigDecimal targetPercent,BigDecimal targetDifferencePp,Integer idleDeveloperCount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReceivableKpi(@JsonUnwrapped CardState card,LocalDate basisDate,@Sensitive Long amount,Integer overdueCount,@Sensitive Long overdueAmount) {}
    public record Kpis(RevenueKpi revenue,ProfitKpi operatingProfit,YtdProfitKpi ytdOperatingProfit,
        UtilizationKpi utilization,ReceivableKpi receivables) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Action(String type,String severity,String state,String title,Integer affectedCount,String unit,
        String description,String sourceMonth,Integer withoutNextAssignmentCount,Integer recommendedDeveloperCount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Actions(@JsonUnwrapped CardState card,LocalDate basisDate,int totalKinds,int urgentKinds,List<Action> items) {}
    public record CloseCheck(String code,String name,String status,int issueCount,String description) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CloseProgress(@JsonUnwrapped CardState card,String basisMonth,String status,Integer completedCount,
        int totalCount,OffsetDateTime checkedAt,List<CloseCheck> checks,List<CloseCheck> incompleteChecks) {}
    public record CostCause(long sourceId,String name,@Sensitive long amount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProjectAlert(long projectId,String projectName,String severity,List<String> statuses,
        @Sensitive BigDecimal marginPercent,BigDecimal budgetUsagePercent,CostCause primaryCostCause,String dataState) {}
    public record ProjectAlerts(@JsonUnwrapped CardState card,LocalDate basisDate,List<ProjectAlert> items) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RevenueCostPoint(String month,String closeStatus,@Sensitive Long revenue,@Sensitive Long cost,
        @Sensitive Long targetRevenue,String actualState,String targetState) {}
    public record RevenueCostTrend(@JsonUnwrapped CardState card,String basisMonth,boolean vatIncluded,List<RevenueCostPoint> months) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkforcePoint(String month,BigDecimal availableMm,BigDecimal requiredMm,BigDecimal shortageMm,Boolean isShortage,String state) {}
    public record WorkforceForecast(@JsonUnwrapped CardState card,LocalDate basisDate,String fromMonth,String toMonth,
        List<String> assumptions,BigDecimal shortageThresholdMm,List<WorkforcePoint> months) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PartRow(Long partId,String partName,String classification,@Sensitive Long revenue,@Sensitive BigDecimal partMarginPercent,String marginState) {}
    public record PartSummary(@JsonUnwrapped CardState card,String basisMonth,String marginLabel,List<PartRow> items) {}
    public record PipelineOpportunity(long opportunityId,String name,LocalDate startDate,String stage,
        BigDecimal probabilityPercent,@Sensitive long expectedAmount,@Sensitive long weightedExpectedAmount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Pipeline(@JsonUnwrapped CardState card,LocalDate basisDate,Integer openOpportunityCount,
        @Sensitive Long weightedExpectedRevenue,String fromMonth,String toMonth,Integer startingOpportunityTotal,List<PipelineOpportunity> startingOpportunities) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CashPoint(String month,@Sensitive Long expectedFinalBalance,@Sensitive Long minimumOperatingCash,Boolean isShortage,String state) {}
    public record CashForecast(@JsonUnwrapped CardState card,LocalDate basisDate,String fromMonth,String toMonth,boolean vatIncluded,List<CashPoint> months) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DashboardResponse(Instant asOf,String basisMonth,String lastClosedMonth,String closeStatus,Kpis kpis,
        Actions actions,CloseProgress closeProgress,ProjectAlerts projectAlerts,RevenueCostTrend revenueCostTrend,
        WorkforceForecast workforceForecast,PartSummary partSummary,Pipeline pipeline,CashForecast cashForecast,List<Warning> warnings) {}
}
