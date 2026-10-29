package com.sicost.common.costallocation;

import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.costallocation.CostAllocationModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CostAllocationService {
    private final CostAllocationMapper mapper;
    private final CostAllocationEngine engine;
    private final Clock clock;
    public CostAllocationService(CostAllocationMapper mapper, CostAllocationEngine engine, Clock clock) {
        this.mapper = mapper; this.engine = engine; this.clock = clock;
    }
    public Result get(String month, Long projectId) {
        CostAnalysisInput.month(month);
        if (projectId != null) {
            if (projectId <= 0) throw CostAnalysisInput.invalid("projectId");
            if (mapper.project(projectId) == null) throw ApiException.notFound("프로젝트가 없습니다.");
        }
        String reference = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        var scope = new Scope(month, projectId);
        if ("CLOSED".equals(mapper.monthStatus(month))) {
            if (mapper.missingSnapshots(scope) > 0)
                throw ApiException.conflict("CLOSED_MONTH", "마감 배분자료 없음", "month");
            return new Result(month, "CLOSED", reference, "KRW", mapper.snapshots(scope), List.of(), List.of(), List.of());
        }
        var calculation = engine.calculate(month, mapper.people(month), mapper.assignments(month), mapper.expenses(month));
        var items = calculation.items().stream().filter(a -> projectId == null || Objects.equals(a.prj_id(), projectId)).toList();
        var expenses = calculation.project_expenses().stream().filter(e -> projectId == null || e.prj_id() == projectId).toList();
        return new Result(month, "OPEN", reference, "KRW", items, expenses,
            projectId == null ? calculation.unallocated() : List.of(), calculation.warnings());
    }
    public ProjectTotal projectTotal(long projectId, String throughMonth) {
        CostAnalysisInput.month(throughMonth);
        if (projectId <= 0) throw CostAnalysisInput.invalid("projectId");
        long amount = 0;
        var missing = new TreeSet<String>();
        for (String month : mapper.projectMonths(new Scope(throughMonth, projectId))) {
            var result = get(month, projectId);
            for (var row : result.items()) {
                if (!Set.of("PROJECT", "RND").contains(row.aloc_ty_cd())) continue;
                if (row.lbr_cst_amt() == null) missing.add(month);
                else amount = Math.addExact(amount, row.lbr_cst_amt());
                amount = Math.addExact(amount, row.drct_expns_amt());
            }
            for (var expense : result.project_expenses())
                if (Set.of("PROJECT", "RND").contains(expense.aloc_ty_cd())) amount = Math.addExact(amount, expense.drct_expns_amt());
        }
        return new ProjectTotal(amount, List.copyOf(missing));
    }
}
