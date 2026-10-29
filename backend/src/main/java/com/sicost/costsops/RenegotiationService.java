package com.sicost.costsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.costallocation.CostAllocationModels.Allocation;
import com.sicost.common.costallocation.CostAllocationService;
import com.sicost.common.security.CostAnalysisActions;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.costsops.RenegotiationModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RenegotiationService {
    private final RenegotiationMapper mapper;
    private final CostAllocationService allocations;
    private final PermissionService permissions;
    private final Clock clock;
    public RenegotiationService(RenegotiationMapper mapper, CostAllocationService allocations,
            PermissionService permissions, Clock clock) {
        this.mapper = mapper;
        this.allocations = allocations;
        this.permissions = permissions;
        this.clock = clock;
    }
    public Result list(Map<String, String> input) {
        permissions.can(CostAnalysisActions.RENEGOTIATIONS_READ);
        return query(RenegotiationInput.parse(input), false);
    }
    public Result query(Query query, boolean full) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String month = YearMonth.from(today).toString();
        if (query.projectId() != null && mapper.project(query.projectId()) == null)
            throw ApiException.notFound("프로젝트가 없습니다.");
        var candidates = mapper.candidates(new Population(today, today.plusMonths(query.windowMonths()),
            query.empType(), query.projectId()));
        if (candidates.isEmpty()) {
            String status = "CLOSED".equals(mapper.monthStatus(month)) ? "CLOSED" : "OPEN";
            return new Result(month, status, month, "KRW", List.of(), List.of(), 0,
                query.paging().page(), query.paging().size(),
                new Summary(0, null, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        var allocation = allocations.get(month, query.projectId());
        Map<Long, Allocation> byAssignment = new HashMap<>();
        for (var row : allocation.items()) {
            if (row.asgn_id() != null) byAssignment.put(row.asgn_id(), row);
        }
        var warnings = new LinkedHashSet<>(allocation.warnings());
        var rows = new ArrayList<Row>();
        for (var candidate : candidates) {
            var source = byAssignment.get(candidate.asgn_id());
            if (source == null && "CLOSED".equals(allocation.status()))
                throw ApiException.conflict("CLOSED_MONTH", "마감 배분자료 없음", "month");
            var row = RenegotiationCalculator.calculate(candidate, source, query.raiseRate(), today,
                "CLOSED".equals(allocation.status()));
            if (row.monthly_cost_amt() == null) warnings.add("투입 " + row.asgn_id() + ": 월 배분원가 없음");
            if (row.mm_qty() == null || row.mm_qty().signum() <= 0) warnings.add("투입 " + row.asgn_id() + ": MM 없음");
            if (row.bill_untprc_amt() == null || row.bill_untprc_amt() <= 0) warnings.add("투입 " + row.asgn_id() + ": 단가 없음 또는 0");
            if (row.margin_rt() == null || row.margin_rt().compareTo(query.marginBelow()) < 0) rows.add(row);
        }
        rows.sort(order(query));
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        for (var row : rows) {
            if (row.margin_rt() == null) continue;
            var revenue = RenegotiationCalculator.revenue(row);
            sales = sales.add(revenue);
            profit = profit.add(revenue.subtract(row.monthly_cost_amt()));
        }
        var summary = new Summary(rows.stream().map(Row::dev_id).distinct().count(),
            RenegotiationCalculator.percentage(profit, sales), sum(rows, Row::annual_profit_increase_amt),
            sum(rows, Row::target_annual_profit_increase_amt));
        var paging = query.paging();
        var items = full ? List.copyOf(rows) : rows.stream().skip(paging.offset()).limit(paging.size()).toList();
        return new Result(month, allocation.status(), month, "KRW", List.copyOf(warnings), items,
            rows.size(), paging.page(), paging.size(), summary);
    }
    private static BigDecimal sum(List<Row> rows, Function<Row, BigDecimal> value) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var row : rows) {
            BigDecimal amount = value.apply(row);
            if (amount == null) return null;
            sum = sum.add(amount);
        }
        return sum;
    }
    private static Comparable<?> value(Row r, String field) {
        return switch (field) {
            case "asgn_id" -> r.asgn_id();
            case "dev_nm" -> r.dev_nm();
            case "emp_se_cd" -> r.emp_se_cd();
            case "prj_nm" -> r.prj_nm();
            case "end_dt" -> r.end_dt();
            case "mm_qty" -> r.mm_qty();
            case "bill_untprc_amt" -> r.bill_untprc_amt();
            case "monthly_cost_amt" -> r.monthly_cost_amt();
            case "margin_rt" -> r.margin_rt();
            case "target_price_amt" -> r.target_price_amt();
            case "required_raise_rt" -> r.required_raise_rt();
            case "annual_profit_increase_amt" -> r.annual_profit_increase_amt();
            case "target_annual_profit_increase_amt" -> r.target_annual_profit_increase_amt();
            default -> throw new IllegalArgumentException("정렬 필드가 아닙니다.");
        };
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<Row> order(Query query) {
        var sort = query.paging().allowedSort(RenegotiationInput.SORT_FIELDS, "end_dt,asc");
        Comparator<Comparable> scalar = Comparator.naturalOrder();
        if (sort.descending()) scalar = scalar.reversed();
        return Comparator.comparing((Row row) -> (Comparable) value(row, sort.field()),
            Comparator.nullsLast(scalar)).thenComparingLong(Row::asgn_id);
    }
}
