package com.sicost.projectcost;

import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("exportProjectCostService")
@Transactional(readOnly = true)
public class ProjectCostService {
    private final com.sicost.apiops.ProjectCostService costs;
    public ProjectCostService(com.sicost.apiops.ProjectCostService costs) { this.costs = costs; }
    public record ProjectCost(long cumulativeCostAmount, List<String> missingSalaryMonths) {}
    public ProjectCost calculate(long projectId, YearMonth throughMonth) {
        var result = costs.calculate(projectId, throughMonth.toString());
        return new ProjectCost(result.cumulative_cost_amt(), result.missing_salary_months());
    }
}
