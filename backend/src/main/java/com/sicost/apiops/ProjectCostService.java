package com.sicost.apiops;

import com.sicost.costallocation.CostAllocationService;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.apiops.ProjectBoardModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectCostService {
    private final CostAllocationService allocations;
    public ProjectCostService(CostAllocationService allocations) { this.allocations = allocations; }
    public ProjectCost calculate(long id, String month) {
        long amount = 0;
        var missing = new ArrayList<String>();
        for (var allocation : allocations.projectThrough(id, month)) {
            for (var row : allocation.items()) {
                if ("PROJECT".equals(row.aloc_ty_cd()) || "RND".equals(row.aloc_ty_cd())) {
                    amount = Math.addExact(amount, row.cost());
                }
            }
            if (allocation.warnings().stream().anyMatch(w -> w.startsWith("급여 미업로드:"))) {
                missing.add(allocation.calculation_month());
            }
        }
        return new ProjectCost(amount, missing.stream().distinct().sorted().toList());
    }
}
