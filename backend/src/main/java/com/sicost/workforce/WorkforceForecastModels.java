package com.sicost.workforce;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class WorkforceForecastModels {
    private WorkforceForecastModels() {}
    public record Source(String month,String job_cd,String grd_cd,BigDecimal supply_mm,BigDecimal demand_mm,
        BigDecimal available_mm,BigDecimal confirmed_mm,BigDecimal extension_mm,BigDecimal temporary_mm,
        BigDecimal idle_cost,BigDecimal average_labor_cost,boolean over_one_mm,boolean missing_cost) {}
    public record WorkforceMonth(String month,String job_cd,String grd_cd,BigDecimal supply_mm,BigDecimal demand_mm,
        BigDecimal remaining_mm,BigDecimal shortage_mm,BigDecimal available_mm,BigDecimal confirmed_mm,
        BigDecimal extension_mm,BigDecimal temporary_mm,BigDecimal utilization_rate,long idle_cost,boolean estimated,
        Long baseline_operating_profit,long average_labor_cost,List<String> warnings) {}
    public record WorkforceShortage(String job_cd,String grd_cd,BigDecimal maximum_shortage_mm,int maximum_shortage_people,
        String first_shortage_month,String recommended_hiring_month,boolean review_immediately,long candidate_count) {}
    public record ForecastPage(List<WorkforceMonth> items,int page,int size,long total,List<WorkforceShortage> shortages,
        List<String> months,BigDecimal target_utilization_rate,String message) {}
    public record ShortagePage(List<WorkforceShortage> items,int page,int size,long total) {}
    public record Read(String from,String to,String job,String grade,LocalDate today) {}
    public record CandidateMatch(String job,String grade,LocalDate availableBy) {}
}
