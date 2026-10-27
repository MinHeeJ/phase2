package com.sicost.projectsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.AssignmentActions;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.projectsops.ProjectAssignmentMonthModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectAssignmentMonthService {
    private final ProjectAssignmentMonthMapper mapper;
    private final PermissionService permissions;
    public ProjectAssignmentMonthService(ProjectAssignmentMonthMapper mapper, PermissionService permissions) {
        this.mapper = mapper;
        this.permissions = permissions;
    }
    public Result getProjectAssignmentMonths(long id, String year) {
        if (id < 1) throw ApiException.badRequest("입력값을 확인하세요.", "id");
        if (year == null || !year.matches("[0-9]{1,4}") || Integer.parseInt(year) < 1) {
            throw ApiException.badRequest("연도를 입력하세요.", "year");
        }
        int selectedYear = Integer.parseInt(year);
        AssignmentActions.can(permissions, "getProjectAssignmentMonths");
        if (!mapper.projectExists(id)) throw ApiException.notFound("프로젝트가 없습니다.");
        LocalDate start = LocalDate.of(selectedYear, 1, 1);
        LocalDate end = LocalDate.of(selectedYear, 12, 31);
        var stored = mapper.months(new Query(id, start, end, YearMonth.from(start).toString(), YearMonth.from(end).toString()));
        Map<Long, List<StoredMonth>> assignments = new LinkedHashMap<>();
        for (var cell : stored) assignments.computeIfAbsent(cell.asgn_id(), key -> new ArrayList<>()).add(cell);
        var rows = new ArrayList<Row>();
        var totals = new TreeMap<String, BigDecimal>();
        for (int month = 1; month <= 12; month++) totals.put(YearMonth.of(selectedYear, month).toString(), BigDecimal.ZERO);
        for (var assignment : assignments.values()) {
            var cells = new ArrayList<Cell>();
            BigDecimal total = BigDecimal.ZERO;
            for (var cell : assignment) {
                cells.add(new Cell(cell.work_ym(), cell.mm_qty()));
                total = total.add(cell.mm_qty());
                totals.merge(cell.work_ym(), cell.mm_qty(), BigDecimal::add);
            }
            var first = assignment.get(0);
            rows.add(new Row(first.asgn_id(), first.dev_id(), first.dev_nm(), List.copyOf(cells), total));
        }
        return new Result(id, selectedYear, List.copyOf(rows), totals.entrySet().stream()
            .map(entry -> new Cell(entry.getKey(), entry.getValue())).toList());
    }
}
