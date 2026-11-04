package com.sicost.opportunitiesops;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class OpportunityForecastModels {
    private OpportunityForecastModels() {}
    public record Query(List<String> months,String job,String grade,String sort,int page,int size,LocalDate today) {}
    public record Developer(long id,String job,String grade,String employee,LocalDate hire,LocalDate resign,Long labor) {}
    public record Leave(long developer,LocalDate start,LocalDate end) {}
    public record Assignment(long id,long project,long developer,String job,String grade,LocalDate start,LocalDate end,String extension) {}
    public record AssignedMonth(long assignment,String month,BigDecimal mm,Long price) {}
    public record Project(long id,String type,LocalDate start,LocalDate end,Long amount) {}
    public record ProjectPlan(long project,String month,String job,String grade,BigDecimal mm) {}
    public record Opportunity(long id,long amount,String start,int months,BigDecimal probability) {}
    public record Demand(long opportunity,String job,String grade,String start,int months,int people,BigDecimal mm,BigDecimal probability) {}
    public record Month(String month,String job_cd,String grd_cd,BigDecimal supply_mm,BigDecimal demand_mm,
        BigDecimal remaining_mm,BigDecimal shortage_mm,BigDecimal available_mm,BigDecimal confirmed_mm,
        BigDecimal extension_mm,BigDecimal temporary_mm,BigDecimal utilization_rate,long idle_cost,
        boolean estimated,Long baseline_operating_profit,long average_labor_cost,List<String> warnings) {}
    public record Shortage(String job_cd,String grd_cd,BigDecimal maximum_shortage_mm,int maximum_shortage_people,
        String first_shortage_month,String recommended_hiring_month,boolean review_immediately,long candidate_count) {}
    public record WorkforcePage(List<Month> items,int page,int size,long total,List<Shortage> shortages,
        List<String> months,int target_utilization_rate,String message) {}
    public record RevenueMonth(String month,long project_revenue,long opportunity_revenue,long weighted_total,long all_won_total) {}
    public record Kpi(long weighted_total,long opportunity_total,long all_won_total,BigDecimal average_demand_mm,
        BigDecimal average_supply_mm,long hiring_job_count) {}
    public record Page(List<RevenueMonth> items,int page,int size,long total,WorkforcePage workforce,Kpi kpi) {}
    public record CandidateFilter(String job,String grade,String available) {}
    public record Temporary(String job,String grade,String start,String end,BigDecimal mm) {}
    public record UtilizationRead(BigDecimal confirmedMm,BigDecimal availableMm,int idleDeveloperCount) {}
}
