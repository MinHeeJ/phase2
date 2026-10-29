package com.sicost.costsops;

import com.sicost.apiops.ProjectCostService;
import com.sicost.common.api.ApiException;
import com.sicost.common.costs.CostAllocationModels.Allocation;
import com.sicost.common.costs.CostStandards;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.costsops.RenegotiationModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RenegotiationService {
    private final RenegotiationMapper mapper;
    private final ProjectCostService costs;
    private final CostStandards standards;
    private final PermissionService permissions;
    private final Clock clock;
    public RenegotiationService(RenegotiationMapper mapper, ProjectCostService costs,
            CostStandards standards, PermissionService permissions, Clock clock) {
        this.mapper = mapper;
        this.costs = costs;
        this.standards = standards;
        this.permissions = permissions;
        this.clock = clock;
    }
    public Data list(Query query) {
        Data result = unpaged(query);
        List<Row> rows = result.items();
        long offset = (long) query.page() * query.size();
        List<Row> page = rows.stream().skip(offset).limit(query.size()).toList();
        return new Data(result.month(), result.closed(), page, rows.size(), query.page(), query.size(),
            result.summary(), result.warnings());
    }
    public Data unpaged(Query query) {
        permissions.can(CostActions.key("listCostRenegotiations"));
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String month = YearMonth.from(today).toString();
        // A project lookup validates existence without limiting the allocation denominator.
        if (query.projectId() != null) costs.allocation(month, query.projectId());
        var source = costs.allocation(month, null);

        BigDecimal target = standards.effective(month, "TARGET_MARGIN_RATE");
        if (target.signum() < 0 || target.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw RenegotiationQuery.error("month");
        }
        Map<Long, Allocation> allocations = source.items().stream().filter(a -> a.asgn_id() != null)
            .collect(Collectors.toMap(Allocation::asgn_id, Function.identity()));
        Set<Long> missingSalary = source.warnings().stream().filter(w -> "SALARY_MISSING".equals(w.code()))
            .map(w -> w.dev_id()).collect(Collectors.toSet());
        var candidates = mapper.candidates(new CandidateQuery(today, today.plusMonths(query.windowMonths()),
            month, query.empType(), query.projectId()));
        List<Row> rows = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Allocation allocation = allocations.get(candidate.asgn_id());
            if (source.closed() && allocation == null) {
                throw ApiException.conflict("VALIDATION_ERROR", "마감 배분자료 없음 — 투입별 점검이 필요합니다.", "month");
            }
            if (allocation != null && allocation.free_sprt()) continue;
            var row = RenegotiationCalculator.row(candidate, allocation, target, query.raiseRate(), today,
                missingSalary.contains(candidate.dev_id()));
            if (RenegotiationCalculator.below(row, query.marginBelow())) rows.add(row);
        }
        Comparator<Row> order = switch (query.sort().split(",")[0]) {
            case "dev_nm" -> Comparator.comparing(Row::dev_nm);
            case "margin_rt" -> Comparator.comparing(Row::margin_rt, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(Row::end_dt);
        };
        if (query.sort().endsWith(",desc")) order = order.reversed();
        rows.sort(order.thenComparingLong(Row::asgn_id));
        return new Data(month, source.closed(), List.copyOf(rows), rows.size(), 0, rows.size(),
            RenegotiationCalculator.summary(rows), rows.stream().flatMap(r -> r.warnings().stream()).distinct().toList());
    }
}
