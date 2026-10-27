package com.sicost.assignmentsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.persistence.AssignmentConcurrencyService;
import com.sicost.common.security.*;
import com.sicost.common.validation.AssignmentRules;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sicost.assignmentsops.AssignmentOperationModels.*;
import com.sicost.assignmentsops.AssignmentOperationModels.Month;

@Service
public class AssignmentOperationService {
    private final AssignmentOperationMapper mapper;
    private final AssignmentConcurrencyService concurrency;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AssignmentOperationService(AssignmentOperationMapper mapper, AssignmentConcurrencyService concurrency,
            CurrentUser user, PermissionService permissions, Clock clock, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.concurrency = concurrency;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private LocalDate today() { return ProjectValues.today(clock); }
    private Project project(long id) {
        var project = mapper.project(id);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        return project;
    }
    private Developer developer(long id) {
        var developer = mapper.developer(id);
        if (developer == null) throw ApiException.notFound("개발자가 없습니다.");
        return developer;
    }
    private Row row(long id) {
        var row = mapper.row(new ReadKey(id, today(), today().getYear()));
        if (row == null) throw ApiException.notFound("투입이 없습니다.");
        return row;
    }
    private Set<YearMonth> closed() {
        var months = new HashSet<YearMonth>();
        mapper.closedMonths().forEach(m -> months.add(YearMonth.parse(m)));
        return months;
    }
    private void reason(AssignmentRules.ChangeType type, LocalDate date, String code, String description) {
        var closed = closed();
        AssignmentRules.requireReason(type, date, clock, closed.stream().max(Comparator.naturalOrder()).orElse(null),
            new AssignmentRules.Reason(code, description,
                code != null && mapper.activeCode(new CodeKey("ASGN_CHG_RSN", code))));
    }
    private PersonPeriod period(long project, long developer, LocalDate start, LocalDate end) {
        return new PersonPeriod(project, developer, start, end, null);
    }
    private void writable(Row row, AssignmentRules.Mutation mutation) {
        var project = project(row.prj_id());
        AssignmentRules.requireProjectWritable(project.use_yn(), project.prj_stts_cd());
        AssignmentRules.requireMutation(row.asgn_stts_cd(), mutation, today(), row.end_dt());
    }
    private List<Warning> warnings(Project project, Developer developer, PersonPeriod period, List<Month> months) {
        List<Warning> warnings = new ArrayList<>();
        for (Month month : months) {
            if (month.person_month_mm_qty().compareTo(BigDecimal.ONE) > 0) {
                warnings.add(new Warning("OVER_MM", "MM 1.0 초과", month.work_ym()));
            }
        }
        if ("PLAN".equals(project.prj_stts_cd())) warnings.add(new Warning("PRE_CONTRACT", "미계약 선투입", null));
        if (mapper.onLeave(period)) warnings.add(new Warning("ON_LEAVE", "휴직 기간 배정", null));
        if ("DP".equals(project.cntrct_ty_cd()) && Set.of("FL", "OS").contains(developer.emp_se_cd())) {
            warnings.add(new Warning("DISPATCH_NONREG", "파견에 프리랜서·외주 배정", null));
        }
        return warnings;
    }
    @Transactional(readOnly = true)
    public CheckResult check(Proposal input) {
        AssignmentActions.can(permissions, "checkAssignments");
        var project = project(input.prj_id());
        var developer = developer(input.dev_id());
        AssignmentRules.requireProjectWritable(project.use_yn(), project.prj_stts_cd());
        if (!"Y".equals(developer.use_yn())) throw AssignmentOperationInput.error("dev_id");
        if (!mapper.activeCode(new CodeKey("JOB", input.job_cd()))) throw AssignmentOperationInput.error("job_cd");
        if (input.cntrct_dev_id() != null) developer(input.cntrct_dev_id());
        AssignmentRules.pricing(project.cntrct_ty_cd(), input.bill_untprc_amt(), input.untprc_base_cd());
        reason(AssignmentRules.ChangeType.START, input.bgn_dt(), input.rsn_cd(), input.rsn_desc());
        var period = new AssignmentRules.Period(input.bgn_dt(), input.end_dt(), input.asgn_rt());
        var closed = closed();
        List<Month> months = new ArrayList<>();
        List<Warning> notices = new ArrayList<>();
        for (var month = YearMonth.from(period.start()); !month.isAfter(YearMonth.from(period.end())); month = month.plusMonths(1)) {
            if (closed.contains(month)) {
                notices.add(new Warning("CLOSED_MONTH", month + "은 마감된 달이라 월별 MM을 만들지 않습니다.", month.toString()));
                continue;
            }
            var automatic = AssignmentRules.automaticMm(period, month);
            var total = mapper.personTotal(new PersonMonth(input.dev_id(), month.toString())).add(automatic);
            months.add(new Month(month.toString(), automatic, automatic, "N", total, "N"));
        }
        notices.addAll(warnings(project, developer,
            period(input.prj_id(), input.dev_id(), input.bgn_dt(), input.end_dt()), months));
        return new CheckResult(List.copyOf(notices), List.copyOf(months));
    }
    private Detail detail(long id) {
        var row = row(id);
        var months = mapper.months(id);
        List<Warning> warnings = warnings(project(row.prj_id()), developer(row.dev_id()),
            period(row.prj_id(), row.dev_id(), row.bgn_dt(), row.end_dt()), months);
        String inUse = mapper.inUse(id);
        if (inUse != null) warnings.add(new Warning("ASSIGNMENT_IN_USE", inUse, null));
        return new Detail(row, List.copyOf(warnings), row.rmrk_desc(), mapper.extendCount(id), mapper.rates(id), months);
    }
    public BatchResult cancel(BatchRequest input) {
        AssignmentActions.can(permissions, "saveAssignmentCancellation");
        return batch(input, true);
    }
    public BatchResult chance(BatchRequest input) {
        AssignmentActions.can(permissions, "saveAssignmentExtChance");
        return batch(input, false);
    }
    private BatchResult batch(BatchRequest input, boolean cancel) {
        List<BatchItem> results = new ArrayList<>();
        for (long id : input.ids()) {
            var version = input.versions().stream().filter(v -> v.asgn_id() == id).findFirst().orElseThrow();
            try {
                results.add(Objects.requireNonNull(transaction.execute(status -> mutate(version, input.value(), cancel))));
            } catch (ApiException error) {
                if (cancel && input.ids().size() == 1) throw error;
                results.add(new BatchItem(id, "EXCLUDED", error.code(), error.getMessage(), null));
            }
        }
        if (cancel && results.stream().noneMatch(i -> "APPLIED".equals(i.status()))) {
            var first = results.get(0);
            throw ApiException.conflict(first.code(), first.message(), null);
        }
        return new BatchResult(List.copyOf(results));
    }
    private BatchItem mutate(Version version, String value, boolean cancel) {
        var locked = concurrency.lockAndCompare(version.asgn_id(), version.updated_at());
        var row = row(version.asgn_id());
        writable(row, cancel ? AssignmentRules.Mutation.CANCEL : AssignmentRules.Mutation.EXT_CHANCE);
        var write = new ChanceWrite(row.asgn_id(), value, user.userId());
        LocalDateTime updated;
        if (cancel) {
            String inUse = mapper.inUse(row.asgn_id());
            if (inUse != null) throw ApiException.conflict("ASSIGNMENT_IN_USE", inUse, null);
            updated = concurrency.advance(locked);
            mapper.cancel(write);
            mapper.deleteOpen(row.asgn_id());
        } else {
            if (Objects.equals(row.ext_psblty_cd(), value)) {
                return new BatchItem(row.asgn_id(), "SKIPPED", null, "같은 값이므로 건너뛰었습니다.", row.updated_at());
            }
            LocalDate application = today().isBefore(row.bgn_dt()) ? row.bgn_dt() : today();
            if (application.isAfter(row.end_dt())) {
                throw ApiException.conflict("ASSIGNMENT_LOCKED", "투입 기간이 끝났습니다.", null);
            }
            updated = concurrency.advance(locked);
            mapper.chance(write);
            mapper.insertHistory(new HistoryWrite(row.asgn_id(), "EXT_CHANCE", application,
                chanceName(row.ext_psblty_cd()), chanceName(value), null, null, null, "N", user.userId()));
        }
        return new BatchItem(row.asgn_id(), "APPLIED", null, cancel ? "배정을 취소했습니다." : "연장 가능성을 변경했습니다.", updated);
    }
    private String chanceName(String value) {
        return value == null ? "미정" : mapper.codeName(new CodeKey("EXT_POSSIBILITY", value));
    }
    @Transactional
    public ReplacementResult replace(long id, ReplacementRequest input) {
        AssignmentActions.can(permissions, "createAssignmentReplacement");
        if (id < 1) throw AssignmentOperationInput.error("id");
        var initial = row(id);
        concurrency.lockProject(initial.prj_id());
        var locked = concurrency.lockAndCompare(id, input.updated_at());
        var original = row(id);
        writable(original, AssignmentRules.Mutation.REPLACE);
        LocalDate date = input.aply_dt();
        if (date == null || !date.isAfter(original.bgn_dt()) || date.isAfter(original.end_dt())) {
            throw ApiException.badRequest("교체일은 투입일 다음 날부터 종료일까지입니다.", "aply_dt");
        }
        if (input.dev_id() == original.dev_id() || input.dev_id() < 1) throw AssignmentOperationInput.error("dev_id");
        var incoming = developer(input.dev_id());
        if (!"Y".equals(incoming.use_yn())) throw AssignmentOperationInput.error("dev_id");
        reason(AssignmentRules.ChangeType.REPLACE, date, input.rsn_cd(), input.rsn_desc());
        if (mapper.overlaps(period(original.prj_id(), input.dev_id(), date, original.end_dt()))) {
            throw ApiException.conflict("OVERLAP_ASSIGNMENT", "같은 사람·프로젝트에 기간이 겹치는 투입이 있습니다.", "dev_id");
        }
        var rates = mapper.rates(id).stream()
            .map(r -> new AssignmentRules.Rate(r.aply_bgn_dt(), r.bill_untprc_amt())).toList();
        var inherited = AssignmentRules.inheritedRates(rates, date, original.end_dt());
        long next = mapper.nextId();
        concurrency.advance(locked);
        mapper.shorten(new EndWrite(id, date.minusDays(1), user.userId()));
        mapper.insertReplacement(new NewAssignment(next, AssignmentRules.allocated(next), original, input, user.userId()));
        var closed = closed();
        var newPeriod = new AssignmentRules.Period(date, original.end_dt(), original.asgn_rt());
        for (var rate : inherited) {
            LocalDate effect = AssignmentRules.financialDate(rate.effectiveDate(), newPeriod, closed);
            if (effect != null) mapper.insertRate(new RateWrite(next, effect, rate.amount(), user.userId()));
        }
        rebuild(id, new AssignmentRules.Period(original.bgn_dt(), date.minusDays(1), original.asgn_rt()), closed);
        rebuild(next, newPeriod, closed);
        String retro = AssignmentRules.retroactive(date, clock) ? "Y" : "N";
        long replaceId = mapper.insertHistory(new HistoryWrite(id, "REPLACE", date, original.dev_nm(),
            row(next).dev_nm() + " · " + date + "~" + original.end_dt() + " 단가 승계", next,
            input.rsn_cd(), input.rsn_desc(), retro, user.userId()));
        long startId = mapper.insertHistory(new HistoryWrite(next, "START", date, null,
            date + "~" + original.end_dt(), null, input.rsn_cd(), input.rsn_desc(), retro, user.userId()));
        List<Change> changes = new ArrayList<>();
        mapper.changes(id).stream().filter(c -> c.asgn_chg_id() == replaceId).forEach(changes::add);
        mapper.changes(next).stream().filter(c -> c.asgn_chg_id() == startId).forEach(changes::add);
        return new ReplacementResult(detail(id), detail(next), List.copyOf(changes));
    }
    private void rebuild(long id, AssignmentRules.Period period, Set<YearMonth> closed) {
        var existing = mapper.months(id).stream().map(m -> new AssignmentRules.Month(YearMonth.parse(m.work_ym()),
            m.auto_mm_qty(), m.mm_qty(), "Y".equals(m.mnl_yn()))).toList();
        var rebuilt = AssignmentRules.rebuildMonths(period, existing, closed);
        mapper.deleteOutsideOpen(new EndWrite(id, period.end(), user.userId()));
        for (var month : rebuilt) {
            if (!closed.contains(month.month())) {
                mapper.saveMonth(new MonthWrite(id, month.month().toString(), month.automatic(), month.quantity(), month.manual() ? "Y" : "N"));
            }
        }
    }
    @Transactional(readOnly = true)
    public List<Detail> exportRows(ExportQuery query) {
        AssignmentActions.can(permissions, "getAssignmentsExport");
        List<Detail> rows = mapper.exportRows(query).stream().map(r -> {
            var months = mapper.months(r.asgn_id());
            var warnings = warnings(project(r.prj_id()), developer(r.dev_id()),
                period(r.prj_id(), r.dev_id(), r.bgn_dt(), r.end_dt()), months);
            return new Detail(r, warnings, r.rmrk_desc(), 0, mapper.rates(r.asgn_id()), months);
        }).filter(r -> query.warning() == null || r.warnings().stream().anyMatch(w -> w.code().equals(query.warning())))
            .sorted(order(query.sort())).toList();
        return rows;
    }
    static Comparator<Detail> order(String sort) {
        Comparator<Detail> result = (a, b) -> 0;
        for (String expression : sort.split(";", -1)) {
            var parts = expression.split(",", -1);
            if (parts.length != 2 || !Set.of("asc", "desc").contains(parts[1])) throw AssignmentOperationInput.error("sort");
            Comparator<Detail> comparator = switch (parts[0]) {
                case "prj_nm" -> Comparator.comparing(d -> d.row().prj_nm());
                case "bgn_dt" -> Comparator.comparing(d -> d.row().bgn_dt());
                case "end_dt" -> Comparator.comparing(d -> d.row().end_dt());
                case "dev_nm" -> Comparator.comparing(d -> d.row().dev_nm());
                case "asgn_no" -> Comparator.comparing(d -> d.row().asgn_no());
                case "job_cd" -> Comparator.comparing(d -> d.row().job_cd());
                case "bill_untprc_amt" -> Comparator.comparing(
                    d -> d.row().bill_untprc_amt(), Comparator.nullsLast(Comparator.naturalOrder()));
                case "year_mm_qty" -> Comparator.comparing(d -> d.row().year_mm_qty());
                case "current_month_mm_qty" -> Comparator.comparing(d -> d.row().current_month_mm_qty());
                case "ext_psblty_cd" -> Comparator.comparing(d -> d.row().ext_psblty_cd(), Comparator.nullsLast(Comparator.naturalOrder()));
                case "asgn_se_cd" -> Comparator.comparing(d -> d.row().asgn_se_cd());
                default -> throw AssignmentOperationInput.error("sort");
            };
            result = result.thenComparing(parts[1].equals("desc") ? comparator.reversed() : comparator);
        }
        return result.thenComparingLong(d -> d.row().asgn_id());
    }
}
