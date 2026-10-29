package com.sicost.costallocation;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CostValues;
import com.sicost.common.validation.ProjectValues;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.costallocation.CostAllocationModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CostAllocationService {
    private final CostAllocationMapper mapper;
    private final Clock clock;

    public CostAllocationService(CostAllocationMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Allocation calculate(String month, Long projectId) {
        String requested = CostValues.month(month, clock).toString();
        if (projectId != null) {
            CostValues.id(projectId, "projectId");
            if (!mapper.projectExists(projectId)) throw ApiException.notFound("프로젝트가 없습니다.");
        }
        return calculateMonth(new Query(requested, projectId));
    }

    public List<Allocation> year(int year, Long projectId) {
        var months = CostValues.months(year, clock);
        if (projectId != null && !mapper.projectExists(CostValues.id(projectId, "projectId"))) {
            throw ApiException.notFound("프로젝트가 없습니다.");
        }
        return months.stream().map(month -> calculateMonth(new Query(month, projectId))).toList();
    }

    public List<Allocation> projectThrough(long projectId, String throughMonth) {
        CostValues.id(projectId, "projectId");
        String month = CostValues.month(throughMonth, clock).toString();
        if (!mapper.projectExists(projectId)) throw ApiException.notFound("프로젝트가 없습니다.");
        var result = new ArrayList<Allocation>();
        for (String included : mapper.projectMonths(new ProjectPeriod(projectId, month))) {
            result.add(calculateMonth(new Query(included, projectId)));
        }
        return List.copyOf(result);
    }

    private Allocation calculateMonth(Query query) {
        // Close status is checked before touching mutable payroll, assignments, rates or expenses.
        boolean closed = "CLOSED".equals(mapper.monthStatus(query.month()));
        Calculation calculation;
        if (closed) {
            var snapshot = mapper.snapshots(query.month());
            var selected = snapshot.stream().filter(row -> query.projectId() == null
                || Objects.equals(row.prj_id(), query.projectId())).toList();
            if (selected.isEmpty() && mapper.expectedCost(query)) {
                throw ApiException.conflict("PART_STANDARD_MISSING", "마감 배분자료 없음: " + query.month(), "month");
            }
            calculation = new Calculation(snapshot, snapshot.stream().anyMatch(r -> "Y".equals(r.mm_ovr_yn()))
                ? List.of("1.0 초과: 마감 배분자료") : List.of(), 0);
        } else {
            calculation = CostAllocationCalculator.calculate(query.month(), new Sources(
                mapper.people(query.month()), mapper.assignments(query.month()), mapper.expenses(query.month()),
                mapper.turnkey(query.month())));
        }
        var selected = calculation.items().stream().filter(row -> query.projectId() == null
            || Objects.equals(row.prj_id(), query.projectId())).toList();
        var warnings = calculation.warnings();
        if (query.projectId() != null) {
            warnings = warnings.stream().filter(w -> !w.startsWith("급여 미업로드:")
                || selected.stream().anyMatch(r -> w.equals("급여 미업로드: dev_id=" + r.dev_id())))
                .toList();
        }
        return new Allocation(query.month(), YearMonth.from(ProjectValues.today(clock)).toString(), closed,
            "원", warnings, selected, calculation.unallocated_cost_amt());
    }
}
