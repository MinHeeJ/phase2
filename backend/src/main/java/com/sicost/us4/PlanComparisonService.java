package com.sicost.us4;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.PermissionService;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.us4.PlanComparisonModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PlanComparisonService {
    private final PlanComparisonMapper mapper;
    private final Clock clock;
    private final PermissionService permissions;
    public PlanComparisonService(PlanComparisonMapper mapper, Clock clock, PermissionService permissions) {
        this.mapper = mapper;
        this.clock = clock;
        this.permissions = permissions;
    }
    public Snapshot list(Query query) { return snapshot(query, false); }
    public Snapshot exportRows(Query query) { return snapshot(query, true); }
    private Snapshot snapshot(Query query, boolean export) {
        permissions.can(export ? "UI-11.download" : "UI-11.read");
        LocalDate today = ProjectValues.today(clock);
        BigDecimal tolerance = mapper.tolerance(YearMonth.from(today).toString());
        if (tolerance == null || tolerance.signum() < 0) {
            throw ApiException.badRequest("계획 대비 투입 기준값을 확인하세요.", "month");
        }
        var sources = mapper.annual(query);
        var orders = new HashMap<String, Integer>();
        sources.forEach(row -> orders.put(row.job_cd(), row.job_order()));
        final BigDecimal effectiveTolerance = tolerance;
        var rows = sources.stream().map(source -> {
            BigDecimal plan = source.planMm().setScale(2, RoundingMode.HALF_UP);
            BigDecimal actual = source.actualMm().setScale(2, RoundingMode.HALF_UP);
            BigDecimal delta = actual.subtract(plan);
            String gap = delta.abs().compareTo(effectiveTolerance) <= 0 ? "MATCH"
                : delta.signum() < 0 ? "SHORT" : "OVER";
            return new Row(source.prj_id(), source.prj_no(), source.prj_nm(), source.job_cd(),
                source.job_nm(), plan, actual, delta, gap);
        }).filter(row -> query.gap() == null || query.gap().equals(row.gap()))
            .sorted(order(query.sort(), orders)).toList();
        var totals = new Totals(rows.stream().map(Row::planMm).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2),
            rows.stream().map(Row::actualMm).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2));
        var page = export ? rows : rows.stream().skip((long) query.page() * query.size()).limit(query.size()).toList();
        return new Snapshot(new Page(page, rows.size(), query.page(), query.size(), totals),
            today, tolerance, mapper.projects(query));
    }
    private Comparator<Row> order(String sort, Map<String, Integer> orders) {
        Comparator<Row> result = (a, b) -> 0;
        for (String part : sort.split(";")) {
            String[] pair = part.split(",");
            Comparator<Row> next = switch (pair[0]) {
                case "prj_nm" -> Comparator.comparing(Row::prj_nm);
                case "job_cd" -> Comparator.comparingInt(row -> orders.get(row.job_cd()));
                case "planMm" -> Comparator.comparing(Row::planMm);
                case "actualMm" -> Comparator.comparing(Row::actualMm);
                default -> Comparator.comparing(Row::gapMm);
            };
            result = result.thenComparing(pair[1].equals("desc") ? next.reversed() : next);
        }
        return result.thenComparingLong(Row::prj_id).thenComparing(Row::job_cd);
    }
}
