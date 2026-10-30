package com.sicost.profit;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.profit.MonthlyProfitModels.*;

@Mapper
public interface MonthlyProfitMapper {
    List<String> closedMonths(int year);
    Map<String,Object> snapshot(SnapshotKey key);
    List<Detail> details(String month);
    List<Entry> entries(String month);
    long adjustment(String month);
    Long annualTax(int year);
    List<TaxMonth> taxMonths(int year);
    Facts facts(String month);
    Project project(ProjectMonth query);
}
