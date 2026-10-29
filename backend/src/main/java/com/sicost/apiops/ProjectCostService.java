package com.sicost.apiops;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.sicost.common.costs.CostAllocationService;
import com.sicost.common.costs.CostAllocationModels.Result;
import java.time.YearMonth;
import static com.sicost.apiops.ProjectBoardModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProjectCostService {
    private final CostAllocationService allocations;
    private final ProjectBoardMapper mapper;
    public ProjectCostService(ProjectBoardMapper mapper) {
        this.mapper = mapper;
        this.allocations = new CostAllocationService(mapper);
    }
    public Result allocation(String month, Long projectId) { return allocations.get(month, projectId); }
    public java.util.List<Result> annualAllocations(long id, YearMonth through) {
        String from = YearMonth.of(through.getYear(), 1).toString();
        return mapper.allocationMonths(new CostQuery(id, through.toString())).stream()
            .filter(month -> month.compareTo(from) >= 0)
            .map(month -> allocations.get(month, id)).toList();
    }
    public ProjectCost calculateAnnual(long id, YearMonth through) {
        return total(allocations.annual(id, through));
    }
    public ProjectCost calculate(long id, String month) {
        return total(mapper.costs(new CostQuery(id, month)));
    }
    private ProjectCost total(java.util.List<CostMonth> rows) {
        long amount = rows.stream().map(CostMonth::amount).reduce(BigDecimal.ZERO, BigDecimal::add).longValueExact();
        var missing = rows.stream().filter(CostMonth::missing_salary).map(CostMonth::month).distinct().sorted().toList();
        return new ProjectCost(amount, missing);
    }
}
