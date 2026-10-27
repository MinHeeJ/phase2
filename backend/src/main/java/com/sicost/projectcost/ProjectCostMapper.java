package com.sicost.projectcost;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectCostMapper {
    record CostQuery(long projectId, String month) {}
    record MonthlyCost(String month, Long amount) {}
    List<MonthlyCost> monthlyCosts(CostQuery query);
    List<String> missingSalaryMonths(CostQuery query);
}
