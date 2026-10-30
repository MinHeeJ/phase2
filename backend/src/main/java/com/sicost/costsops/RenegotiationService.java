package com.sicost.costsops;

import com.sicost.common.security.CostActions;
import com.sicost.common.security.PermissionService;
import com.sicost.common.validation.CostValues;
import com.sicost.common.validation.ProjectValues;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.shared.SharedReadMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.costsops.RenegotiationModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RenegotiationService {
    public static final Set<String> SORT_FIELDS = Set.of("asgn_id", "dev_nm", "emp_se_cd", "prj_nm",
        "end_dt", "days_remaining", "mm_qty", "bill_untprc_amt", "allocated_cost_amt", "margin_rt",
        "target_price_amt", "required_raise_rt", "assumed_annual_profit_increase_amt", "target_annual_profit_increase_amt");
    private final RenegotiationMapper mapper;
    private final CostAllocationService allocations;
    private final SharedReadMapper parameters;
    private final Clock clock;
    private final PermissionService permissions;

    public RenegotiationService(RenegotiationMapper mapper, CostAllocationService allocations,
            SharedReadMapper parameters, Clock clock, PermissionService permissions) {
        this.mapper = mapper;
        this.allocations = allocations;
        this.parameters = parameters;
        this.clock = clock;
        this.permissions = permissions;
    }

    public Result list(Query query, boolean full) {
        permissions.can(CostActions.RENEGOTIATION_READ);
        var filters = query.filters();
        var today = ProjectValues.today(clock);
        String month = YearMonth.from(today).toString();
        var sort = query.paging().allowedSort(SORT_FIELDS, "end_dt,asc");
        var allocation = allocations.calculate(month, filters.projectId());
        var warnings = new ArrayList<>(allocation.warnings());
        BigDecimal goal = parameter("TARGET_MARGIN_RATE", month, warnings);
        BigDecimal configuredRaise = parameter("RENEGO_RAISE_RATE", month, warnings);
        BigDecimal raise = filters.raiseRate() == null ? configuredRaise : filters.raiseRate();
        if (raise == null) raise = BigDecimal.valueOf(5);
        if (raise.signum() < 0 || raise.compareTo(BigDecimal.valueOf(50)) > 0) {
            warnings.add("기준값 범위 오류: RENEGO_RAISE_RATE; 기본 인상률 5% 적용");
            raise = BigDecimal.valueOf(5);
        }
        if (goal != null && (goal.signum() < 0 || goal.compareTo(BigDecimal.valueOf(100)) >= 0)) {
            warnings.add("기준값 범위 오류: TARGET_MARGIN_RATE");
            goal = null;
        }
        Map<Long, AllocationRow> byAssignment = new HashMap<>();
        for (var row : allocation.items()) {
            if (row.asgn_id() != null) byAssignment.put(row.asgn_id(), row);
        }
        var sources = mapper.candidates(new SourceQuery(today, today.plusMonths(filters.windowMonths()),
            month, filters.empType(), filters.projectId()));
        var rows = new ArrayList<Row>();
        BigDecimal totalRevenue = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
        for (var source : sources) {
            var row = byAssignment.get(source.asgn_id());
            if (row == null) {
                warnings.add("배분자료 없음: asgn_id=" + source.asgn_id());
                continue;
            }
            if (!RenegotiationCalculator.below(row, filters.marginBelow())) continue;
            rows.add(RenegotiationCalculator.row(source, row, goal, raise, today));
            var revenue = RenegotiationCalculator.revenue(row);
            totalCost = totalCost.add(BigDecimal.valueOf(row.cost()));
            if (revenue == null || revenue.signum() == 0) {
                warnings.add("단가 또는 MM 없음: asgn_id=" + source.asgn_id());
            } else {
                totalRevenue = totalRevenue.add(revenue);
            }
        }
        rows.sort(comparator(sort.field(), sort.descending()));
        var summary = new Summary(rows.stream().map(Row::dev_id).distinct().count(),
            CostValues.percent(totalRevenue.subtract(totalCost), totalRevenue),
            sum(rows, Row::assumed_annual_profit_increase_amt), sum(rows, Row::target_annual_profit_increase_amt));
        long offset = query.paging().offset();
        List<Row> selected = full ? List.copyOf(rows) : rows.stream().skip(offset).limit(query.paging().size()).toList();
        return new Result(month, allocation.reference_month(), allocation.closed(), "원", List.copyOf(warnings),
            selected, rows.size(), query.paging().page(), query.paging().size(), summary, goal, raise);
    }

    private BigDecimal parameter(String key, String month, List<String> warnings) {
        var parameter = parameters.effectiveParam(key, month);
        if (parameter == null || parameter.numericValue() == null) {
            warnings.add("기준값 없음: " + key);
            return null;
        }
        return parameter.numericValue();
    }

    private Long sum(List<Row> rows, Function<Row, Long> value) {
        if (rows.stream().anyMatch(r -> value.apply(r) == null)) return null;
        return rows.stream().map(value).reduce(0L, Math::addExact);
    }

    private Comparator<Row> comparator(String field, boolean descending) {
        Function<Row, ? extends Comparable<?>> key = switch (field) {
            case "asgn_id" -> Row::asgn_id;
            case "dev_nm" -> Row::dev_nm;
            case "emp_se_cd" -> Row::emp_se_cd;
            case "prj_nm" -> Row::prj_nm;
            case "days_remaining" -> Row::days_remaining;
            case "mm_qty" -> Row::mm_qty;
            case "bill_untprc_amt" -> Row::bill_untprc_amt;
            case "allocated_cost_amt" -> Row::allocated_cost_amt;
            case "margin_rt" -> Row::margin_rt;
            case "target_price_amt" -> Row::target_price_amt;
            case "required_raise_rt" -> Row::required_raise_rt;
            case "assumed_annual_profit_increase_amt" -> Row::assumed_annual_profit_increase_amt;
            case "target_annual_profit_increase_amt" -> Row::target_annual_profit_increase_amt;
            default -> Row::end_dt;
        };
        Comparator<Row> result = (left, right) -> compare(key.apply(left), key.apply(right), descending);
        return result.thenComparingLong(Row::asgn_id);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Comparable left, Comparable right, boolean descending) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        return descending ? right.compareTo(left) : left.compareTo(right);
    }
}
