package com.sicost.people;

import com.sicost.common.api.ApiException;
import com.sicost.common.costallocation.CostAllocationService;
import com.sicost.common.costallocation.CostAllocationModels.Allocation;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.people.CostPeopleModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CostPeopleService {
    private final CostPeopleMapper mapper;
    private final CostAllocationService allocations;
    private final PermissionService permissions;
    private final Clock clock;
    public CostPeopleService(CostPeopleMapper mapper, CostAllocationService allocations,
            PermissionService permissions, Clock clock) {
        this.mapper = mapper;
        this.allocations = allocations;
        this.permissions = permissions;
        this.clock = clock;
    }
    private record Data(String month, String reference, String status, int months,
        Map<Long, List<Monthly>> people, Map<Long, Project> projects) {}

    public Page list(CostPeopleInput query) { return page(query, false); }
    public Page full(CostPeopleInput query) { return page(query, true); }
    private Page page(CostPeopleInput query, boolean full) {
        permissions.can(CostAnalysisActions.PEOPLE_READ);
        var data = load(query);
        var rows = data.people().entrySet().stream()
            .map(e -> row(e.getValue(), data, query)).filter(r -> matches(r, query))
            .sorted(order(query)).toList();
        var warnings = new LinkedHashSet<String>();
        rows.forEach(r -> warnings.addAll(r.warnings()));
        var summary = summary(rows, data, warnings);
        var items = full ? rows : rows.stream().skip(query.paging().offset()).limit(query.paging().size()).toList();
        return new Page(data.month(), data.status(), data.reference(), "KRW", List.copyOf(warnings),
            items, rows.size(), query.paging().page(), query.paging().size(), summary);
    }
    public Breakdown breakdown(long id, CostPeopleInput query) {
        permissions.can(CostAnalysisActions.PEOPLE_READ);
        if (mapper.person(id) == null) throw ApiException.notFound("개발자가 없습니다.");
        var data = load(query);
        var months = data.people().getOrDefault(id, List.of());
        if (months.isEmpty()) throw ApiException.notFound("해당 기간의 개인 자료가 없습니다.");
        var person = row(months, data, query);
        return new Breakdown(data.month(), data.status(), data.reference(), "KRW", person.warnings(),
            person, months.stream().flatMap(m -> m.items().stream()).toList(), months);
    }
    private Data load(CostPeopleInput query) {
        var reference = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        int count = query.year() == reference.getYear() ? reference.getMonthValue() : 12;
        var people = new TreeMap<Long, List<Monthly>>();
        var projects = new TreeMap<Long, Project>();
        String status = "OPEN";
        for (int i = 1; i <= count; i++) {
            String month = YearMonth.of(query.year(), i).toString();
            var result = allocations.get(month, null);
            status = result.status();
            mapper.projects(month).forEach(p -> projects.put(p.asgn_id(), p));
            var payroll = new HashMap<Long, Payroll>();
            if (!"CLOSED".equals(status)) mapper.payroll(month).forEach(p -> payroll.put(p.dev_id(), p));
            for (var person : mapper.people(month)) {
                var items = result.items().stream().filter(a -> a.dev_id() == person.dev_id()).toList();
                var unallocated = result.unallocated().stream().filter(a -> a.dev_id() == person.dev_id()).findFirst();

                var warnings = new ArrayList<String>();
                for (String warning : result.warnings()) {
                    if (warning.endsWith(": " + person.dev_id()) || warning.contains(": " + person.dev_id() + "/") || items.stream().anyMatch(a ->
                            a.asgn_id() != null && warning.endsWith(": " + a.asgn_id())))
                        warnings.add(month + " " + warning);
                }
                if ("CLOSED".equals(status)) warnings.add(month + " 급여 세부·경비 항목: 마감 스냅샷에 없음");
                else if (!payroll.containsKey(person.dev_id()) || payroll.get(person.dev_id()).slry_amt() == null)
                    warnings.add(month + " 급여 미업로드: " + person.dev_id());
                people.computeIfAbsent(person.dev_id(), key -> new ArrayList<>()).add(new Monthly(month, status,
                    person, payroll.get(person.dev_id()), items,
                    unallocated.map(a -> a.lbr_cst_amt()).orElse(null),
                    unallocated.map(a -> a.drct_expns_amt()).orElse(0L), List.copyOf(warnings)));
            }
        }
        return new Data(YearMonth.of(query.year(), count).toString(), reference.toString(), status, count, people, projects);
    }
    private Row row(List<Monthly> months, Data data, CostPeopleInput query) {
        Person p = months.get(months.size() - 1).person();
        var items = months.stream().flatMap(m -> m.items().stream()).toList();
        var projects = new TreeMap<Long, Project>();
        data.projects().values().stream().filter(a -> a.dev_id() == p.dev_id()).forEach(a -> projects.put(a.asgn_id(), a));
        var types = new TreeSet<String>();
        projects.values().forEach(a -> types.add(a.cntrct_ty_cd()));
        if (items.stream().anyMatch(a -> "IDLE".equals(a.aloc_ty_cd()))) types.add("IDLE");
        var warnings = new LinkedHashSet<String>();
        months.forEach(m -> warnings.addAll(m.warnings()));
        BigDecimal labor = BigDecimal.ZERO, expense = BigDecimal.ZERO, revenue = BigDecimal.ZERO;
        boolean missingLabor = false, missingRevenue = false;
        BigDecimal mm = new BigDecimal("0.00");
        var prices = new ArrayList<BigDecimal>();
        for (var m : months) {
            boolean freelance = Set.of("FL", "OS").contains(m.person().emp_se_cd());
            boolean assigned = m.items().stream().anyMatch(a -> a.mm_qty() != null && a.mm_qty().signum() > 0);
            if (freelance && !assigned) continue;
            if (m.items().isEmpty()) missingLabor = true;
            for (var a : m.items()) {
                if (a.lbr_cst_amt() == null) missingLabor = true;
                else labor = labor.add(BigDecimal.valueOf(a.lbr_cst_amt()));
                expense = expense.add(BigDecimal.valueOf(a.drct_expns_amt()));
                if (a.mm_qty() != null) mm = mm.add(a.mm_qty());
                Project project = a.asgn_id() == null ? null : projects.get(a.asgn_id());
                boolean internal = "RND".equals(a.aloc_ty_cd()) || "INDIRECT".equals(a.aloc_ty_cd())
                    || "IDLE".equals(a.aloc_ty_cd());
                boolean excluded = query.excludeTurnkeyPrice() && project != null && "TK".equals(project.cntrct_ty_cd());
                if (!internal && !excluded) {
                    if (a.sls_amt() == null) missingRevenue = true;
                    else revenue = revenue.add(BigDecimal.valueOf(a.sls_amt()));
                    if (a.bill_untprc_amt() != null) prices.add(BigDecimal.valueOf(a.bill_untprc_amt()));
                    else if (project != null && "TK".equals(project.cntrct_ty_cd()) && a.sls_amt() != null
                            && a.mm_qty() != null && a.mm_qty().signum() > 0)
                        prices.add(BigDecimal.valueOf(a.sls_amt()).divide(a.mm_qty(), 12, RoundingMode.HALF_UP));
                }
            }
        }
        if (missingLabor) warnings.add("급여 결손: " + p.dev_id());
        if (missingRevenue) warnings.add("개인 참고매출 결손: " + p.dev_id());
        BigDecimal cost = missingLabor ? null : labor.add(expense);
        BigDecimal sales = missingRevenue ? null : revenue;
        BigDecimal profit = cost == null || sales == null ? null : sales.subtract(cost);
        BigDecimal margin = percent(profit, sales);
        if (margin == null) warnings.add("이익률 분모 없음: " + p.dev_id());
        int divisor = "AVERAGE".equals(query.view()) ? data.months() : 1;
        return new Row(p.dev_id(), p.mng_no(), p.dev_nm(), p.affiliation_name(), p.vendor_id(), p.bsn_part_id(),
            p.pstn_cd(), p.emp_se_cd(), p.job_cd(), p.grd_cd(), p.on_leave(), List.copyOf(projects.values()),
            String.join(",", types), mm, mean(prices),
            payroll(months, Payroll::slry_amt, divisor), payroll(months, Payroll::alwnc_amt, divisor),
            payroll(months, Payroll::sins_cmpny_amt, divisor), payroll(months, Payroll::sins_emp_amt, divisor),
            payroll(months, Payroll::whtax_amt, divisor), payroll(months, Payroll::rtrpay_acml_amt, divisor),
            payroll(months, Payroll::real_pay_amt, divisor), divide(missingLabor ? null : labor, divisor),
            payroll(months, a -> a.team_expns_amt(), divisor), payroll(months, a -> a.project_expns_amt(), divisor),
            divide(cost, divisor), divide(sales, divisor), divide(profit, divisor), margin,
            items.stream().anyMatch(a -> "Y".equals(a.mm_ovr_yn())), List.copyOf(warnings));
    }
    private static BigDecimal payroll(List<Monthly> months, Function<Payroll, Long> field, int divisor) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var m : months) {
            boolean freelance = Set.of("FL", "OS").contains(m.person().emp_se_cd());
            if (freelance && m.items().stream().noneMatch(a -> a.mm_qty() != null && a.mm_qty().signum() > 0)) continue;
            if (m.payroll() == null || field.apply(m.payroll()) == null) return null;
            sum = sum.add(BigDecimal.valueOf(field.apply(m.payroll())));
        }
        return divide(sum, divisor);
    }
    private static boolean matches(Row r, CostPeopleInput q) {
        return (q.empType() == null || q.empType().equals(r.emp_se_cd()))
            && (q.partId() == null || q.partId().equals(r.bsn_part_id()))
            && (q.vendorId() == null || ("SELF".equals(q.vendorId()) ? r.vendor_id() == null
                : q.vendorId().equals(Objects.toString(r.vendor_id(), ""))))
            && (q.contractType() == null || Arrays.asList(r.cntrct_ty_cd().split(",")).contains(q.contractType()))
            && (q.job() == null || q.job().equals(r.job_cd()))
            && (!q.lossOnly() || r.profit_amt() != null && r.profit_amt().signum() < 0)
            && (!q.overlapOnly() || r.overlap());
    }
    private Summary summary(List<Row> rows, Data data, Set<String> warnings) {
        var eligible = rows.stream().filter(r -> !r.on_leave()).toList();
        var prices = rows.stream().map(Row::bill_untprc_amt).filter(Objects::nonNull).toList();
        var activeCounts = new ArrayList<BigDecimal>();
        var paidCounts = new ArrayList<BigDecimal>();
        var utilization = new ArrayList<BigDecimal>();
        var paidRates = new ArrayList<BigDecimal>();
        boolean missingDenominator = false;
        for (int i = 1; i <= data.months(); i++) {
            String month = data.month().substring(0, 4) + "-" + String.format("%02d", i);
            long direct = 0, active = 0, paid = 0;
            for (var r : rows) {
                var m = data.people().get(r.dev_id()).stream().filter(v -> v.month().equals(month)).findFirst();
                if (m.isEmpty() || !"DIRECT".equals(m.get().person().duty_se_cd())) continue;
                direct++;
                if (m.get().items().stream().anyMatch(a -> a.mm_qty() != null && a.mm_qty().signum() > 0)) active++;
                if (m.get().items().stream().anyMatch(a -> {
                    Project project = a.asgn_id() == null ? null : data.projects().get(a.asgn_id());
                    return project != null && Set.of("MO", "DP").contains(project.cntrct_ty_cd())
                        && "PROJECT".equals(a.aloc_ty_cd()) && !"Y".equals(a.free_sprt_yn())
                        && a.mm_qty() != null && a.mm_qty().signum() > 0;
                })) paid++;
            }
            activeCounts.add(BigDecimal.valueOf(active));
            paidCounts.add(BigDecimal.valueOf(paid));
            if (direct == 0) missingDenominator = true;
            else {
                utilization.add(BigDecimal.valueOf(active).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(direct), 12, RoundingMode.HALF_UP));
                paidRates.add(BigDecimal.valueOf(paid).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(direct), 12, RoundingMode.HALF_UP));
            }
        }
        if (missingDenominator) warnings.add("가동률·가득률 분모 없음");
        var groups = new TreeMap<String, List<Row>>();
        eligible.forEach(r -> groups.computeIfAbsent(Objects.toString(r.job_cd(), "미지정") + "/"
            + Objects.toString(r.grd_cd(), "미지정"), key -> new ArrayList<>()).add(r));
        var contributions = groups.values().stream().map(g -> new Contribution(g.get(0).job_cd(),
            g.get(0).grd_cd(), g.size(), average(g, Row::profit_amt))).toList();
        return new Summary(rows.size(), eligible.size(), prices.size(),
            (int) rows.stream().filter(r -> r.profit_amt() != null && r.profit_amt().signum() < 0).count(),
            mean(activeCounts), mean(paidCounts), missingDenominator ? null : mean(utilization),
            missingDenominator ? null : mean(paidRates), mean(prices), average(eligible, Row::total_cost_amt),
            average(eligible, Row::profit_amt), contributions);
    }
    private static BigDecimal average(List<Row> rows, Function<Row, BigDecimal> field) {
        if (rows.isEmpty() || rows.stream().anyMatch(r -> field.apply(r) == null)) return null;
        return mean(rows.stream().map(field).toList());
    }
    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }
    private static BigDecimal divide(BigDecimal value, int divisor) {
        return value == null ? null : value.divide(BigDecimal.valueOf(divisor), 0, RoundingMode.DOWN);
    }
    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() == 0 ? null
            : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Comparator<Row> order(CostPeopleInput query) {
        var sort = query.paging().allowedSort(CostPeopleInput.SORTS, "dev_nm,asc");
        Comparator<Comparable> scalar = Comparator.naturalOrder();
        if (sort.descending()) scalar = scalar.reversed();
        return Comparator.comparing((Row row) -> (Comparable) value(row, sort.field()), Comparator.nullsLast(scalar))
            .thenComparingLong(Row::dev_id);
    }
    private static Object value(Row r, String field) {
        return switch (field) {
            case "dev_id" -> r.dev_id(); case "dev_nm" -> r.dev_nm();
            case "affiliation_name" -> r.affiliation_name(); case "pstn_cd" -> r.pstn_cd();
            case "emp_se_cd" -> r.emp_se_cd(); case "mm_qty" -> r.mm_qty();
            case "bill_untprc_amt" -> r.bill_untprc_amt(); case "slry_amt" -> r.slry_amt();
            case "total_cost_amt" -> r.total_cost_amt(); case "personal_revenue_amt" -> r.personal_revenue_amt();
            case "profit_amt" -> r.profit_amt(); case "margin_rt" -> r.margin_rt();
            default -> throw new IllegalArgumentException("정렬 필드가 아닙니다.");
        };
    }
}
