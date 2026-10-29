package com.sicost.apiops;

import com.sicost.common.costallocation.CostAllocationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.apiops.ProjectBoardModels.*;

@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ProjectCostService {
    private final CostAllocationService allocation;
    public ProjectCostService(CostAllocationService allocation) { this.allocation = allocation; }
    public ProjectCost calculate(long id, String month) {
        var total = allocation.projectTotal(id, month);
        return new ProjectCost(total.amount(), total.missing_salary_months());
    }
}
