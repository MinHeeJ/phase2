package com.sicost.analysis;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.analysis.AnalysisModels.*;

@Mapper
public interface AnalysisMapper {
    record Project(long id,String name,Long customerId,Long partId,String type,BigDecimal amount,BigDecimal budget) {}
    record MonthProject(String month,long projectId) {}
    record ProjectCost(long projectId,BigDecimal amount,long people) {}
    record Target(String month,BigDecimal revenue,BigDecimal profit) {}
    List<Project> projects(Filters filters);
    String closeStatus(String month);
    List<ProjectCost> closedCosts(String month);
    boolean hasPayroll(String month);
    Target target(String month);
    Long forecast(ForecastWrite write);
    int forecastValue(ForecastValue value);
    boolean wasClosed(String month);
    List<SavedForecast> savedForecasts(String month);
}
