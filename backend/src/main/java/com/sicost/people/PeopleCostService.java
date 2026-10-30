package com.sicost.people;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.CostActions;
import com.sicost.common.security.PermissionService;
import com.sicost.common.validation.CostValues;
import com.sicost.common.validation.ProjectValues;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.costallocation.CostAllocationModels.Allocation;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.*;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.people.PeopleModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PeopleCostService {
    private final PeopleMapper mapper;
    private final CostAllocationService allocation;
    private final Clock clock;
    private final PermissionService permissions;
    public PeopleCostService(PeopleMapper mapper, CostAllocationService allocation, Clock clock,
            PermissionService permissions) {
        this.mapper = mapper;
        this.allocation = allocation;
        this.clock = clock;
        this.permissions = permissions;
    }
    private record Data(List<String> months, List<PersonMonth> people, List<ProjectLink> links,
            List<Allocation> allocations, List<String> warnings) {}
    private Data data(Query q) {
        List<String> months = CostValues.months(q.year(), clock);
        var period = new Period(months.get(0), months.get(months.size() - 1));
        var allocations = allocation.year(q.year(), null);
        var people = mapper.people(period);
        var warnings = new LinkedHashSet<String>();
        allocations.forEach(a -> warnings.addAll(a.warnings()));
        people.stream().filter(p -> !p.payroll_present()).forEach(p ->
            warnings.add("급여 미업로드: dev_id=" + p.dev_id() + " " + p.month()));
        return new Data(months, people, mapper.projects(period), allocations, List.copyOf(warnings));
    }
    public PeopleList list(Query q) { return list(q, false); }
    public PeopleList full(Query q) { return list(q, true); }
    private PeopleList list(Query q, boolean full) {
        permissions.can(CostActions.PEOPLE_READ);
        var data = data(q);
        var grouped = new TreeMap<Long,List<PersonMonth>>();
        data.people().forEach(p -> grouped.computeIfAbsent(p.dev_id(), k -> new ArrayList<>()).add(p));
        List<PeopleRow> rows = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            var sources = entry.getValue();
            var links = data.links().stream().filter(l -> l.dev_id() == entry.getKey()).toList();
            if (!matches(q.filters(), sources, links)) continue;
            var row = row(q, data, sources, links);
            if (q.lossOnly() && row.prft_amt() >= 0) continue;
            if (q.overlapOnly() && !"Y".equals(row.mm_ovr_yn())) continue;
            rows.add(row);
        }
        var sort = q.paging().allowedSort(PeopleInput.SORTS, "dev_id,asc");
        Comparator<PeopleRow> comparator = comparator(sort.field());
        if (sort.descending()) comparator = comparator.reversed();
        rows.sort(comparator.thenComparingLong(PeopleRow::dev_id));
        var summary = summary(rows, data);
        long start = Math.min(q.paging().offset(), rows.size());
        var items = full ? List.copyOf(rows) : List.copyOf(rows.subList((int) start,
            (int) Math.min(start + q.paging().size(), rows.size())));
        return new PeopleList(last(data.months()), reference(), data.allocations().stream().allMatch(Allocation::closed),
            "원", data.warnings(), items, rows.size(), q.paging().page(), q.paging().size(), summary);
    }
    private boolean matches(Filters f, List<PersonMonth> sources, List<ProjectLink> links) {
        return sources.stream().anyMatch(p -> (f.empType() == null || f.empType().equals(p.emp_se_cd()))
            && (f.partId() == null || f.partId().equals(p.bsn_part_id()))
            && (f.vendorId() == null || f.vendorId().equals(p.vendor_id())))
            && (f.contractType() == null || links.stream().anyMatch(l -> f.contractType().equals(l.cntrct_ty_cd())))
            && (f.job() == null || sources.stream().anyMatch(p -> f.job().equals(p.job_cd()))
                || links.stream().anyMatch(l -> f.job().equals(l.job_cd())));
    }
    private PeopleRow row(Query q, Data data, List<PersonMonth> sources, List<ProjectLink> links) {
        PersonMonth person = sources.get(sources.size() - 1);
        var allocations = personAllocations(data, person.dev_id());
        int divisor = "AVERAGE".equals(q.view()) ? data.months().size() : 1;
        long revenue = 0;
        BigDecimal mm = BigDecimal.ZERO;
        boolean priced = false;
        long price = 0;
        for (var a : allocations) {
            if (!Set.of("IDLE", "INDIRECT").contains(a.aloc_ty_cd())) mm = mm.add(zero(a.mm_qty()));
            var link = links.stream().filter(l -> Objects.equals(a.asgn_id(), l.asgn_id())).findFirst().orElse(null);
            boolean excluded = q.excludeTurnkeyPrice() && link != null && "TK".equals(link.cntrct_ty_cd());
            if (!excluded) revenue = Math.addExact(revenue, a.sls_amt());
            if (!excluded && (a.bill_untprc_amt() != null || (link != null && "TK".equals(link.cntrct_ty_cd())))) {
                priced = true;
                price = Math.addExact(price, a.sls_amt());
            }
        }
        long cost = 0;
        for (String month : data.months()) {
            var monthly = allocations.stream().filter(a -> a.cst_ym().equals(month)).toList();
            var source = sources.stream().filter(p -> p.month().equals(month)).findFirst().orElse(null);
            boolean closed = data.allocations().stream().anyMatch(a -> a.closed() && a.calculation_month().equals(month));
            // The snapshot owns closed totals; an open FL/OS without MM still has its unallocated source cost.
            long amount = monthly.stream().mapToLong(AllocationRow::cost).sum();
            if (!closed && source != null) amount = source.indv_lbr_cst_amt() + source.team_expns_amt() + source.project_expns_amt();
            cost = Math.addExact(cost, amount);
        }
        long profit = revenue - cost;
        var names = links.stream().map(ProjectLink::prj_nm).distinct().toList();
        var types = links.stream().map(ProjectLink::cntrct_ty_cd).distinct().toList();
        boolean idle = allocations.stream().anyMatch(a -> "IDLE".equals(a.aloc_ty_cd()));
        return new PeopleRow(person.dev_id(), person.dev_nm(), person.vendor_name(), person.pstn_cd(),
            person.emp_se_cd(), person.bsn_part_id(), names,
            data.months().get(0) + " ~ " + last(data.months()), types,
            mm.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP), priced ? price / divisor : null,
            sum(sources, PersonMonth::slry_amt) / divisor, sum(sources, PersonMonth::alwnc_amt) / divisor,
            sum(sources, PersonMonth::sins_cmpny_amt) / divisor, sum(sources, PersonMonth::sins_emp_amt) / divisor,
            sum(sources, PersonMonth::whtax_amt) / divisor, sum(sources, PersonMonth::real_pay_amt) / divisor,
            sum(sources, PersonMonth::rtrpay_acml_amt) / divisor, sum(sources, PersonMonth::indv_lbr_cst_amt) / divisor,
            sum(sources, PersonMonth::team_expns_amt) / divisor, sum(sources, PersonMonth::project_expns_amt) / divisor,
            cost / divisor, revenue / divisor, profit / divisor,
            types.size() == 1 && types.contains("IN") ? null : CostValues.percent(BigDecimal.valueOf(profit), BigDecimal.valueOf(revenue)),
            allocations.stream().filter(a -> "IDLE".equals(a.aloc_ty_cd())).mapToLong(AllocationRow::cost).sum() / divisor,
            allocations.stream().anyMatch(a -> "Y".equals(a.mm_ovr_yn())) ? "Y" : "N", names.size() > 1 || idle);
    }
    private Summary summary(List<PeopleRow> rows, Data data) {
        var ids = rows.stream().map(PeopleRow::dev_id).collect(java.util.stream.Collectors.toSet());
        BigDecimal working = BigDecimal.ZERO, earning = BigDecimal.ZERO;
        BigDecimal utilization = BigDecimal.ZERO, earningRate = BigDecimal.ZERO;
        boolean undefined = false;
        for (String month : data.months()) {
            var direct = data.people().stream().filter(p -> ids.contains(p.dev_id()) && p.month().equals(month)
                && "DIRECT".equals(p.duty_se_cd())).toList();
            long workers = direct.stream().filter(p -> personAllocations(data, p.dev_id()).stream()
                .anyMatch(a -> a.cst_ym().equals(month) && zero(a.mm_qty()).signum() > 0
                    && !"IDLE".equals(a.aloc_ty_cd()) && !"INDIRECT".equals(a.aloc_ty_cd()))).count();
            long earners = direct.stream().filter(p -> personAllocations(data, p.dev_id()).stream()
                .anyMatch(a -> a.cst_ym().equals(month) && zero(a.mm_qty()).signum() > 0 && "N".equals(a.free_sprt_yn())
                    && data.links().stream().anyMatch(l -> Objects.equals(a.asgn_id(), l.asgn_id())
                        && Set.of("MO", "DP").contains(l.cntrct_ty_cd())))).count();
            working = working.add(BigDecimal.valueOf(workers));
            earning = earning.add(BigDecimal.valueOf(earners));
            if (direct.isEmpty()) undefined = true;
            else {
                utilization = utilization.add(BigDecimal.valueOf(workers).divide(BigDecimal.valueOf(direct.size()), 16, RoundingMode.HALF_UP));
                earningRate = earningRate.add(BigDecimal.valueOf(earners).divide(BigDecimal.valueOf(direct.size()), 16, RoundingMode.HALF_UP));
            }
        }
        var nonLeave = rows.stream().filter(r -> data.people().stream().anyMatch(p -> p.dev_id() == r.dev_id()
            && !p.on_leave())).toList();
        var prices = rows.stream().filter(r -> r.bill_untprc_amt() != null).toList();
        Map<String,List<PeopleRow>> groups = new TreeMap<>();
        for (var row : nonLeave) {
            var person = data.people().stream().filter(p -> p.dev_id() == row.dev_id()).reduce((a,b) -> b).orElseThrow();
            groups.computeIfAbsent(Objects.toString(person.job_cd(), "") + "\u0000" + Objects.toString(person.grd_cd(), ""), k -> new ArrayList<>()).add(row);
        }
        var contributions = groups.entrySet().stream().map(e -> {
            String[] key = e.getKey().split("\u0000", -1);
            return new Contribution(key[0], key[1], e.getValue().size(), average(e.getValue(), PeopleRow::prft_amt));
        }).toList();
        return new Summary(rows.size(), decimalAverage(working, data.months().size()), decimalAverage(earning, data.months().size()),
            average(prices, r -> r.bill_untprc_amt()), average(nonLeave, PeopleRow::tot_cst_amt),
            average(nonLeave, PeopleRow::prft_amt), rows.stream().filter(r -> r.prft_amt() < 0).count(),
            undefined ? null : decimalAverage(utilization.multiply(BigDecimal.valueOf(100)), data.months().size()),
            undefined ? null : decimalAverage(earningRate.multiply(BigDecimal.valueOf(100)), data.months().size()), contributions);
    }
    public PersonBreakdown breakdown(long id, Query q) {
        permissions.can(CostActions.PEOPLE_READ);
        CostValues.id(id, "devId");
        if (!mapper.personExists(id)) throw ApiException.notFound("개발자가 없습니다.");
        var data = data(q);
        var items = personAllocations(data, id).stream().map(a -> {
            String name = data.links().stream().filter(l -> Objects.equals(a.prj_id(), l.prj_id()))
                .map(ProjectLink::prj_nm).findFirst().orElse(null);
            if ("IDLE".equals(a.aloc_ty_cd())) name = "대기";
            else if ("INDIRECT".equals(a.aloc_ty_cd())) name = "간접 인력";
            return new BreakdownRow(a.cst_ym(), a.asgn_id(), a.prj_id(), name, a.aloc_ty_cd(),
                a.mm_qty(), a.bill_untprc_amt(), a.lbr_cst_amt(), a.drct_expns_amt(), a.sls_amt(),
                a.sls_amt() - a.cost(), "IDLE".equals(a.aloc_ty_cd()) ? a.cost() : 0, a.mm_ovr_yn());
        }).toList();
        return new PersonBreakdown(last(data.months()), reference(), data.allocations().stream().allMatch(Allocation::closed),
            "원", data.warnings(), id, items);
    }
    private List<AllocationRow> personAllocations(Data data, long id) {
        return data.allocations().stream().flatMap(a -> a.items().stream())
            .filter(a -> Objects.equals(a.dev_id(), id)).toList();
    }
    private static long sum(List<PersonMonth> rows, ToLongFunction<PersonMonth> value) {
        return rows.stream().mapToLong(value).reduce(0, Math::addExact);
    }
    private static Long average(List<PeopleRow> rows, ToLongFunction<PeopleRow> value) {
        return rows.isEmpty() ? null : rows.stream().mapToLong(value).reduce(0, Math::addExact) / rows.size();
    }
    private static BigDecimal decimalAverage(BigDecimal value, int count) {
        return value.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static String last(List<String> months) { return months.get(months.size() - 1); }
    private String reference() { return YearMonth.from(ProjectValues.today(clock)).toString(); }
    private Comparator<PeopleRow> comparator(String field) {
        return switch (field) {
            case "dev_nm" -> Comparator.comparing(PeopleRow::dev_nm);
            case "mm_qty" -> Comparator.comparing(PeopleRow::mm_qty);
            case "bill_untprc_amt" -> Comparator.comparing(PeopleRow::bill_untprc_amt, Comparator.nullsLast(Long::compare));
            case "mrgn_rt" -> Comparator.comparing(PeopleRow::mrgn_rt, Comparator.nullsLast(BigDecimal::compareTo));
            case "tot_cst_amt" -> Comparator.comparingLong(PeopleRow::tot_cst_amt);
            case "prft_amt" -> Comparator.comparingLong(PeopleRow::prft_amt);
            case "sls_amt" -> Comparator.comparingLong(PeopleRow::sls_amt);
            case "slry_amt" -> Comparator.comparingLong(PeopleRow::slry_amt);
            default -> Comparator.comparingLong(PeopleRow::dev_id);
        };
    }
}
