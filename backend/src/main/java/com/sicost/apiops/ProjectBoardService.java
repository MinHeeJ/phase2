package com.sicost.apiops;

import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.apiops.ProjectBoardModels.*;

@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ProjectBoardService {
    private final ProjectBoardMapper mapper;
    private final ProgressParameterService parameters;
    private final ProjectCostService costs;
    private final PermissionService permissions;
    private final Clock clock;
    public ProjectBoardService(ProjectBoardMapper mapper, ProgressParameterService parameters,
            ProjectCostService costs, PermissionService permissions, Clock clock) {
        this.mapper = mapper; this.parameters = parameters; this.costs = costs;
        this.permissions = permissions; this.clock = clock;
    }
    public BoardData get(Map<String, String> input) {
        permissions.can(ProjectActions.key("getProjectBoard"));
        var query = ProjectBoardInput.of(input);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String month = YearMonth.from(today).toString();
        var params = parameters.get(month);
        var sources = mapper.projects(today);
        var rows = sources.stream().map(p -> ProjectProgressCalculator.calculate(p, mapper.phases(p.prj_id()),
            "TK".equals(p.cntrct_ty_cd()) ? costs.calculate(p.prj_id(), month) : null, params, today)).toList();
        var stale = rows.stream().filter(r -> r.memo_elapsed_days() != null && r.memo_elapsed_days() > 14)
            .map(r -> new StaleProject(r.prj_id(), r.prj_nm(), r.memo_elapsed_days())).toList();
        var summary = new BoardSummary(rows.size(), count(rows, "정상"), count(rows, "주의"), count(rows, "위험"),
            rows.stream().mapToInt(BoardRow::open_issue_count).sum(),
            sources.stream().mapToInt(BoardSource::high_open_issue_count).sum(),
            rows.stream().mapToInt(BoardRow::overdue_issue_count).sum(), stale.size());
        var parts = new HashMap<Long, Long>();
        sources.forEach(p -> parts.put(p.prj_id(), p.bsn_part_id()));
        var filtered = rows.stream().filter(r -> query.light() == null || query.light().equals(r.light()))
            .filter(r -> query.type() == null || query.type().equals(r.cntrct_ty_cd()))
            .filter(r -> query.part() == null || query.part().equals(parts.get(r.prj_id())))
            .sorted(order(query.sort())).toList();
        long offset = (long) query.page() * query.size();
        var items = filtered.stream().skip(offset).limit(query.size()).toList();
        return new BoardData(summary, items, filtered.size(), query.page(), query.size(), stale);
    }
    private static int count(List<BoardRow> rows, String light) {
        return Math.toIntExact(rows.stream().filter(r -> light.equals(r.light())).count());
    }
    private static int rank(String light) { return "위험".equals(light) ? 0 : "주의".equals(light) ? 1 : 2; }
    private static Object value(BoardRow r, String field) {
        return switch (field) {
            case "light" -> rank(r.light());
            case "reasons" -> String.join(" ", r.reasons());
            case "prj_no" -> r.prj_no(); case "prj_nm" -> r.prj_nm(); case "custName" -> r.custName();
            case "pmName" -> r.pmName(); case "cntrct_ty_cd" -> r.cntrct_ty_cd();
            case "currentPhase" -> r.currentPhase() == null ? null : r.currentPhase().phs_seq();
            case "plan_prgs_rt" -> r.plan_prgs_rt(); case "actl_prgs_rt" -> r.actl_prgs_rt();
            case "schedule_diff_rt" -> r.schedule_diff_rt(); case "cost_use_rt" -> r.cost_use_rt();
            case "cost_progress_diff_rt" -> r.cost_progress_diff_rt();
            case "open_issue_count" -> r.open_issue_count(); case "overdue_issue_count" -> r.overdue_issue_count();
            case "memo_elapsed_days" -> r.memo_elapsed_days(); case "last_memo_dt" -> r.last_memo_dt();
            default -> throw new IllegalArgumentException("정렬 필드가 아닙니다.");
        };
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<BoardRow> order(String sort) {
        Comparator<BoardRow> fallback = Comparator.comparingInt((BoardRow r) -> rank(r.light()))
            .thenComparing(BoardRow::prj_nm).thenComparingLong(BoardRow::prj_id);
        if (sort == null) return fallback;
        String[] split = sort.split(",");
        Comparator<Comparable> scalar = Comparator.naturalOrder();
        if ("desc".equals(split[1])) scalar = scalar.reversed();
        Comparator<Comparable> nullable = Comparator.nullsLast(scalar);
        return Comparator.comparing((BoardRow r) -> (Comparable) value(r, split[0]), nullable).thenComparing(fallback);
    }
}
