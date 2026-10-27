package com.sicost.apiops;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.apiops.ProjectBoardModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectCostService {
    private final ProjectBoardMapper mapper;
    public ProjectCostService(ProjectBoardMapper mapper) { this.mapper = mapper; }
    public ProjectCost calculate(long id, String month) {
        var rows = mapper.costs(new CostQuery(id, month));
        long amount = rows.stream().map(CostMonth::amount).reduce(BigDecimal.ZERO, BigDecimal::add).longValueExact();
        var missing = rows.stream().filter(CostMonth::missing_salary).map(CostMonth::month).distinct().sorted().toList();
        return new ProjectCost(amount, missing);
    }
}
