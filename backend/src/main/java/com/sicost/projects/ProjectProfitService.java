package com.sicost.projects;

import com.sicost.apiops.ProjectCostService;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.common.api.ApiException;
import com.sicost.common.costs.CostStandards;
import com.sicost.common.security.*;
import java.math.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.projects.ProjectProfitModels.*;
import static com.sicost.projects.ProjectProfitCalculator.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProjectProfitService {
    private final ProjectProfitMapper mapper;
    private final ProjectCostService costs;
    private final BillingRevenueQuery revenue;
    private final CostStandards standards;
    private final PermissionService permissions;
    private final Clock clock;
    public ProjectProfitService(ProjectProfitMapper mapper, ProjectCostService costs,
            BillingRevenueQuery revenue, CostStandards standards, PermissionService permissions, Clock clock) {
        this.mapper = mapper;
        this.costs = costs;
        this.revenue = revenue;
        this.standards = standards;
        this.permissions = permissions;
        this.clock = clock;
    }
    static ApiException invalid(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "조회 조건을 확인하세요.", field);
    }
    static long id(String value, String field) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException ex) { throw invalid(field); }
    }
    static int integer(String value, String field) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw invalid(field); }
    }
    private YearMonth through(String value) {
        int year = integer(value, "year");
        var now = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if (year < 1 || year > now.getYear()) throw invalid("year");
        return YearMonth.of(year, year == now.getYear() ? now.getMonthValue() : 12);
    }
    private Source require(String value) {
        var source = mapper.project(id(value, "id"));
        if (source == null) throw ApiException.notFound("프로젝트가 없습니다.");
        return source;
    }
    private static String choice(Map<String,String> input, String key, Set<String> choices) {
        String value = input.get(key);
        if (value != null && !choices.contains(value)) throw invalid(key);
        return value;
    }
    public Page list(Map<String,String> input) {
        permissions.can(CostActions.key("listCostProjects"));
        for (var key : input.keySet()) if (!Set.of("year","type","partId","status","q","page","size","sort").contains(key)) throw invalid(key);
        var through = through(input.get("year"));
        int page = integer(input.getOrDefault("page", "0"), "page");
        int size = integer(input.getOrDefault("size", "50"), "size");
        if (page < 0) throw invalid("page");
        if (!Set.of(20,50,100).contains(size)) throw invalid("size");
        String sort = input.getOrDefault("sort", "prj_nm,asc");
        if (!Set.of("prj_nm,asc","prj_nm,desc","cost,asc","cost,desc","margin,asc","margin,desc","budget_rate,asc","budget_rate,desc").contains(sort)) throw invalid("sort");
        String q = input.get("q");
        if (q != null && q.length() > 200) throw invalid("q");
        var filter = new Filter(through.getYear(), choice(input,"type",Set.of("TK","MO","DP","IN")),
            input.containsKey("partId") ? id(input.get("partId"),"partId") : null,
            choice(input,"status",Set.of("PLAN","RUN","HOLD","END","LOST")), q);
        var rows = new ArrayList<>(mapper.list(filter).stream().map(s -> detail(s,through).summary()).toList());
        Comparator<Row> comparator = switch (sort.split(",")[0]) {
            case "cost" -> Comparator.comparingLong(Row::cost);
            case "margin" -> Comparator.comparing(Row::margin, Comparator.nullsLast(Comparator.naturalOrder()));
            case "budget_rate" -> Comparator.comparing(Row::budget_rate, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(r -> r.project().prj_nm());
        };
        if (sort.endsWith(",desc")) comparator = comparator.reversed();
        rows.sort(comparator.thenComparingLong(r -> r.project().prj_id()));
        var summary = new Summary(rows.stream().map(Row::revenue).filter(Objects::nonNull).mapToLong(Long::longValue).sum(),
            rows.stream().map(Row::profit).filter(Objects::nonNull).mapToLong(Long::longValue).sum(),
            (int) rows.stream().filter(Row::low_margin).count(),
            (int) rows.stream().filter(r -> "RISK".equals(r.budget_alert())).count(), rows.stream().mapToLong(Row::rnd_cost).sum());
        long offset = (long)page * size;
        return new Page(rows.stream().skip(offset).limit(size).toList(), rows.size(), page, size, summary, through.toString());
    }
    public Detail get(String id, String year) {
        permissions.can(CostActions.key("getCostProject"));
        var through = through(year);
        return detail(require(id), through);
    }
    public Causes causes(String id, String year) {
        permissions.can(CostActions.key("getCostProjectCauses"));
        var through = through(year);
        return detail(require(id), through).causes();
    }
    public Object simulate(String id) {
        permissions.can(CostActions.key("simulateCostProject"));
        require(id);
        throw new ApiException(400, "VALIDATION_ERROR", "시뮬레이션 요청·응답 필드가 승인되지 않아 가상 계산을 실행할 수 없습니다. 입력값은 저장하지 않습니다.", null);
    }
    private Detail detail(Source source, YearMonth through) {
        long project = source.prj_id();
        int year = through.getYear();
        boolean internal = "IN".equals(source.cntrct_ty_cd());
        boolean turnkey = "TK".equals(source.cntrct_ty_cd());
        var from = YearMonth.of(year,1);
        var allocations = costs.annualAllocations(project, through);
        var billing = internal ? Collections.<com.sicost.billingsops.BillingModels.ProjectRevenue>emptyList()
            : revenue.projectRevenue(project,from,through);
        var names = new HashMap<Long,String>();
        mapper.people(project).forEach(p -> names.put(p.dev_id(),p.dev_nm()));
        var months = new ArrayList<Month>();
        var people = new ArrayList<PersonMonth>();
        var warnings = new LinkedHashSet<String>();
        long cumulative = 0;
        for (var month = from; !month.isAfter(through); month = month.plusMonths(1)) {
            String m = month.toString();
            var allocation = allocations.stream().filter(a -> a.month().equals(m)).findFirst().orElse(null);
            long labor = 0, expenses = 0, freeCost = 0, reference = 0;
            BigDecimal mm = BigDecimal.ZERO, freeMm = BigDecimal.ZERO;
            boolean referenceMissing = false;
            var monthWarnings = new LinkedHashSet<String>();
            if (allocation != null) {
                var projectPeople = new HashSet<Long>();
                for (var a : allocation.items()) {
                    if (!Set.of("PROJECT","RND").contains(a.aloc_ty_cd())) continue;
                    projectPeople.add(a.dev_id());
                    labor = Math.addExact(labor,a.lbr_cst_amt());
                    expenses = Math.addExact(expenses,a.drct_expns_amt());
                    BigDecimal assigned = a.mm_qty() == null ? BigDecimal.ZERO : a.mm_qty();
                    mm = mm.add(assigned);
                    if (a.free_sprt()) {
                        freeMm = freeMm.add(assigned);
                        freeCost = Math.addExact(freeCost,Math.addExact(a.lbr_cst_amt(),a.drct_expns_amt()));
                    }
                    if (a.sls_amt() == null) referenceMissing = true;
                    else reference = Math.addExact(reference,a.sls_amt());
                    people.add(new PersonMonth(m,a.dev_id(),names.get(a.dev_id()),a.asgn_id(),assigned,
                        a.lbr_cst_amt(),a.drct_expns_amt(),a.sls_amt(),a.free_sprt(),a.pre_cntrct()));
                }
                for (var e : allocation.expenses()) expenses = Math.addExact(expenses,e.expns_amt());
                allocation.warnings().stream().filter(w -> w.dev_id() == null || projectPeople.contains(w.dev_id()))
                    .forEach(w -> monthWarnings.add(w.message()));
            }
            long amount = Math.addExact(labor,expenses);
            cumulative = Math.addExact(cumulative,amount);
            long sales = billing.stream().filter(r -> r.month().equals(m)).mapToLong(r -> r.confirmed_supply_amt()).sum();
            months.add(new Month(m,internal ? null : sales,labor,expenses,amount,mm.setScale(2),freeMm.setScale(2),freeCost,
                internal ? null : margin(sales,amount), source.trgt_cst_amt() == null ? null : source.trgt_cst_amt()-cumulative,
                internal || referenceMissing ? null : reference, allocation != null && allocation.closed(),List.copyOf(monthWarnings)));
            warnings.addAll(monthWarnings);
        }
        long cost = cumulative;
        Long sales = internal ? null : months.stream().map(Month::revenue).mapToLong(Long::longValue).sum();
        Long profit = sales == null ? null : Math.subtractExact(sales,cost);
        BigDecimal actualMm = months.stream().map(Month::mm).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2);
        BigDecimal plannedMm = mapper.plans(new Period(project,from.toString(),through.toString())).stream()
            .map(Plan::mm_qty).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2);
        BigDecimal target = internal ? null : source.trgt_mrgn_rt() != null ? source.trgt_mrgn_rt()
            : standards.effective(through.toString(),"TARGET_MARGIN_RATE");
        BigDecimal threshold = standards.effective(through.toString(),"RENEG_COST_RATE");
        BigDecimal margin = internal ? null : margin(sales,cost);
        Long forecastCost = null;
        BigDecimal estimated = null;
        if (turnkey) {
            var forecast = mapper.forecast(new Period(project,from.toString(),through.toString()));
            boolean ended = source.end_dt() != null && !YearMonth.from(source.end_dt()).isAfter(through);
            boolean complete = ended || !forecast.isEmpty();
            BigDecimal remaining = BigDecimal.ZERO;
            for (var f : forecast) {
                if (f.dev_id() == null || f.monthly_cost() == null || f.mm_qty() == null) complete = false;
                else remaining = remaining.add(f.mm_qty().multiply(BigDecimal.valueOf(f.monthly_cost())));
            }
            if (complete) {
                forecastCost = Math.addExact(cost, remaining.setScale(0,RoundingMode.FLOOR).longValueExact());
                estimated = margin(source.cur_cntrct_amt(),forecastCost);
            } else warnings.add("남은 계획 인력·MM 또는 최근 월 인건비 근거가 없어 예상 최종 원가를 계산할 수 없습니다.");
        } else if (!internal) {
            var current = months.get(months.size()-1);
            estimated = current.margin();
        }
        if (!internal && (source.trgt_cst_amt() == null || source.trgt_cst_amt() <= 0)) warnings.add("목표 원가(예산)가 없거나 0원으로 예산 소진율을 계산할 수 없습니다.");
        if (people.stream().anyMatch(p -> p.reference_revenue() == null)) warnings.add("개인 참고매출 근거가 없는 인력이 있어 단가 대비 원가 원인은 잠정값입니다.");
        var causes = ProjectProfitCalculator.causes(source,year,people,plannedMm,threshold,List.copyOf(warnings));
        // Project-only expenses contribute to the MM unit cost, never to a fabricated person.
        var causeRows = new ArrayList<>(causes.items());
        BigDecimal staffing = actualMm.signum() == 0 ? BigDecimal.ZERO : actualMm.subtract(plannedMm).max(BigDecimal.ZERO)
            .multiply(BigDecimal.valueOf(cost)).divide(actualMm,0,RoundingMode.FLOOR);
        causeRows.replaceAll(c -> c.code().equals("STAFFING") ? new Cause(c.code(),c.label(),staffing,c.basis(),c.owner(),c.recommendation(),c.route(),c.unavailable()) : c);
        causeRows.sort(Comparator.comparing(Cause::amount).reversed().thenComparingInt(c -> List.of("STAFFING","PRICING","FREE").indexOf(c.code())));
        BigDecimal progressExcess = !turnkey || source.trgt_cst_amt() == null || source.trgt_cst_amt() <= 0 || source.actl_prgs_rt() == null ? null
            : BigDecimal.valueOf(cost).subtract(BigDecimal.valueOf(source.trgt_cst_amt()).multiply(source.actl_prgs_rt()).movePointLeft(2)).setScale(0,RoundingMode.FLOOR);
        BigDecimal residual = progressExcess == null ? null : progressExcess.subtract(causeRows.stream().map(Cause::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
        String primary = causeRows.stream().filter(c -> c.amount().signum()>0).map(Cause::code).findFirst().orElse(null);
        causes = new Causes(project,year,List.copyOf(causeRows),primary,progressExcess,residual,List.copyOf(warnings));
        BigDecimal budgetRate = internal || source.trgt_cst_amt() == null || source.trgt_cst_amt() <= 0 ? null : ratio(BigDecimal.valueOf(cost),BigDecimal.valueOf(source.trgt_cst_amt()));
        BigDecimal judged = estimated;
        var current = months.get(months.size()-1);
        boolean low = !internal && (lowMarginFor(sales,cost,target)
            || (turnkey ? forecastCost != null && lowMarginFor(source.cur_cntrct_amt(),forecastCost,target)
                : lowMarginFor(current.revenue(),current.cost(),target)));
        Source displayed = internal ? new Source(source.prj_id(),source.prj_no(),source.prj_nm(),source.cntrct_ty_cd(),
            source.prj_stts_cd(),source.bsn_part_id(),source.part_name(),source.pm_name(),null,null,null,null,
            source.actl_prgs_rt(),source.bgn_dt(),source.end_dt()) : source;
        var row = new Row(displayed,plannedMm,actualMm,sales,cost,profit,margin,forecastCost,estimated,target,
            judged == null || target == null ? null : judged.subtract(target).setScale(1,RoundingMode.HALF_UP),budgetRate,
            internal ? "UNAVAILABLE" : budgetAlert(cost,source.trgt_cst_amt()),low,
            unit(sales,actualMm),unit(cost,actualMm),unit(profit,actualMm),
            months.stream().map(Month::free_mm).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2),
            months.stream().mapToLong(Month::free_cost).sum(),internal ? cost : 0,primary,List.copyOf(warnings));
        return new Detail(row,through.toString(),List.copyOf(months),List.copyOf(people),causes,
            "연도 1월부터 기준월까지의 분석입니다. 기존 진행 현황·현황판은 전년도 원가를 포함한 전체 누적이므로 기간이 다를 수 있습니다.");
    }
    private BigDecimal unit(Long value, BigDecimal mm) {
        return value == null || mm.signum() == 0 ? null : BigDecimal.valueOf(value).divide(mm,0,RoundingMode.FLOOR);
    }
}
