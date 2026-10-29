package com.sicost.common.costs;

import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.apiops.ProjectBoardModels.CostMonth;
import com.sicost.apiops.ProjectBoardModels.CostQuery;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static com.sicost.common.costs.CostAllocationModels.*;

public final class CostAllocationService {
    private final ProjectBoardMapper mapper;
    private final CostAllocationEngine engine = new CostAllocationEngine();
    public CostAllocationService(ProjectBoardMapper mapper) { this.mapper = mapper; }

    public static String month(String value) {
        if (value == null || !value.matches("(?!0000)[0-9]{4}-(0[1-9]|1[0-2])")) {
            throw new ApiException(400, "VALIDATION_ERROR", "연월을 확인하세요.", "month");
        }
        return value;
    }
    public static Long projectId(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw new ApiException(400, "VALIDATION_ERROR", "프로젝트 번호를 확인하세요.", "projectId");
        }
    }
    public Result get(String requestedMonth, Long projectId) {
        String month = month(requestedMonth);
        if (projectId != null && mapper.allocationProject(projectId) == null) {
            throw ApiException.notFound("프로젝트가 없습니다.");
        }
        Result result;
        if ("CLOSED".equals(mapper.allocationMonthStatus(month))) {
            var snapshots = mapper.allocationSnapshots(month);
            if (snapshots.isEmpty()) {
                throw ApiException.conflict("VALIDATION_ERROR", "마감 배분자료 없음 — 점검이 필요합니다.", "month");
            }
            if (projectId != null && snapshots.stream().noneMatch(a -> Objects.equals(a.prj_id(), projectId))) {
                throw ApiException.conflict("VALIDATION_ERROR", "프로젝트 마감 배분자료 없음 — 점검이 필요합니다.", "month");
            }
            result = new Result(month, true, snapshots, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        } else {
            result = engine.calculate(month, mapper.allocationSalaries(month), mapper.allocationAssignments(month),
                mapper.allocationExpenses(month), mapper.allocationTurnkey(month));
        }
        if (projectId == null) return result;
        return new Result(month, result.closed(), result.items().stream()
            .filter(a -> Objects.equals(a.prj_id(), projectId)).toList(), result.expenses().stream()
            .filter(e -> e.prj_id() == projectId).toList(), result.warnings(), result.unallocated());
    }
    public List<CostMonth> costs(long projectId, String from, String through) {
        month(through);
        if (from != null) month(from);
        var months = mapper.allocationMonths(new CostQuery(projectId, through));
        List<CostMonth> costs = new ArrayList<>();
        for (String m : months) {
            if (from != null && m.compareTo(from) < 0) continue;
            Result result = get(m, projectId);
            long amount = 0;
            for (var a : result.items()) {
                if (Set.of("PROJECT", "RND").contains(a.aloc_ty_cd())) {
                    amount = Math.addExact(amount, Math.addExact(a.lbr_cst_amt(), a.drct_expns_amt()));
                }
            }
            for (var e : result.expenses()) amount = Math.addExact(amount, e.expns_amt());
            Set<Long> people = new HashSet<>();
            result.items().forEach(a -> people.add(a.dev_id()));
            boolean missing = result.warnings().stream().anyMatch(w -> "SALARY_MISSING".equals(w.code())
                && people.contains(w.dev_id()));
            costs.add(new CostMonth(m, BigDecimal.valueOf(amount), missing));
        }
        return List.copyOf(costs);
    }
    public List<CostMonth> annual(long projectId, YearMonth through) {
        return costs(projectId, YearMonth.of(through.getYear(), 1).toString(), through.toString());
    }
}
