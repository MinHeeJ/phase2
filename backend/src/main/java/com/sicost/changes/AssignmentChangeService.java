package com.sicost.changes;

import com.sicost.common.api.ApiException;
import com.sicost.common.persistence.AssignmentConcurrencyService;
import com.sicost.common.security.*;
import com.sicost.common.validation.AssignmentRules;
import com.sicost.common.validation.AssignmentRules.Period;
import com.sicost.common.validation.AssignmentRules.Month;
import com.sicost.common.validation.ProjectValues;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.changes.AssignmentChangeModels.*;
import static com.sicost.common.validation.AssignmentRules.*;

@Service
@Transactional(readOnly = true)
public class AssignmentChangeService {
    private final AssignmentChangeMapper mapper;
    private final AssignmentConcurrencyService concurrency;
    private final PermissionService permissions;
    private final CurrentUser user;
    private final Clock clock;

    public AssignmentChangeService(AssignmentChangeMapper mapper, AssignmentConcurrencyService concurrency,
            PermissionService permissions, CurrentUser user, Clock clock) {
        this.mapper = mapper;
        this.concurrency = concurrency;
        this.permissions = permissions;
        this.user = user;
        this.clock = clock;
    }
    private State require(long id) {
        if (id < 1) throw AssignmentChangeInput.error("id");
        var state = mapper.state(id);
        if (state == null) throw ApiException.notFound("투입이 없습니다.");
        return state;
    }
    private ChangeRow row(long id, long key) {
        var row = mapper.row(new ChangeKey(id, key));
        if (row == null) throw ApiException.notFound("변경 이력이 없습니다.");
        return row;
    }
    public Changes list(long id, String type) {
        AssignmentActions.can(permissions, "listAssignmentChanges");
        AssignmentChangeInput.type(type);
        require(id);
        var items = mapper.list(new ChangeFilter(id, type));
        return new Changes(items, items.size());
    }
    private Set<YearMonth> closed() {
        var result = new HashSet<YearMonth>();
        mapper.closedMonths().forEach(m -> result.add(YearMonth.parse(m)));
        return result;
    }
    private YearMonth lastClosed(Set<YearMonth> months) {
        return months.stream().max(Comparator.naturalOrder()).orElse(null);
    }
    private void reason(ChangeType type, LocalDate date, String code, String memo,
            Set<YearMonth> closed) {
        if (reasonRequired(type, date, clock, lastClosed(closed)) && (code == null || code.isBlank())) {
            throw ApiException.badRequest("사유를 골라 주세요.", "rsn_cd");
        }
        requireReason(type, date, clock, lastClosed(closed),
            new Reason(code, memo, code != null && mapper.codeName(new CodeKey("ASGN_CHG_RSN", code)) != null));
    }
    private void writable(State state, Mutation mutation, LocalDate date) {
        requireProjectWritable(state.use_yn(), state.prj_stts_cd());
        if ("ENDED".equals(state.asgn_stts_cd())) {
            throw ApiException.conflict("ASSIGNMENT_LOCKED", "변경할 수 없는 투입 상태입니다.", null);
        }
        requireMutation(state.asgn_stts_cd(), mutation, date, state.end_dt());
    }
    private Period period(State state) { return new Period(state.bgn_dt(), state.end_dt(), state.asgn_rt()); }
    private void rebuild(long id) {
        var state = require(id);
        var closed = closed();
        var existing = mapper.months(id).stream().map(m -> new Month(YearMonth.parse(m.work_ym()),
            m.auto_mm_qty(), m.mm_qty(), "Y".equals(m.mnl_yn()))).toList();
        var rebuilt = rebuildMonths(period(state), existing, closed);
        mapper.deleteOutsideMonths(id);
        for (var month : rebuilt) {
            if (closed.contains(month.month())) continue;
            mapper.month(new MonthWrite(id, month.month().toString(), month.automatic(), month.quantity(),
                month.manual() ? "Y" : "N"));
        }
    }
    private String code(String group, String code) {
        if (code == null) return "미정";
        String name = mapper.codeName(new CodeKey(group, code));
        if (name == null) throw AssignmentChangeInput.error(group.equals("ASSIGN_KIND") ? "asgn_se_cd" : "ext_psblty_cd");
        return name;
    }
    private String periodText(LocalDate start, LocalDate end, String kind, Long person) {
        return start + " ~ " + end + " · " + code("ASSIGN_KIND", kind)
            + (person == null ? "" : " · 계약상 인력 #" + person);
    }
    private String money(Long amount) {
        return amount == null ? "–" : String.format(Locale.KOREA, "%,d원", amount);
    }
    private Detail detail(long id, List<Warning> extra) {
        var today = ProjectValues.today(clock);
        var all = new ArrayList<>(mapper.warnings(id));
        all.addAll(extra);
        int extensions = (int) mapper.list(new ChangeFilter(id, "EXTEND")).stream()
            .filter(r -> "N".equals(r.fixed_yn())).count();
        return new Detail(mapper.header(new ReadKey(id, today, YearMonth.from(today).toString(),
            Integer.toString(today.getYear()))), mapper.remark(id), extensions, mapper.rates(id), mapper.months(id), all);
    }
    @Transactional
    public SaveResult create(long id, ChangeInput input) {
        AssignmentActions.can(permissions, "createAssignmentChange");
        var initial = require(id);
        concurrency.lockProject(initial.prj_id());
        var locked = concurrency.lockAndCompare(id, input.updated_at());
        var state = require(id);
        concurrency.advance(locked);
        ChangeType type = ChangeType.valueOf(input.asgn_chg_ty_cd());
        LocalDate application = input.aply_dt();
        writable(state, Mutation.valueOf(type.name()), application);
        if (application == null) throw AssignmentChangeInput.error("aply_dt");
        if (application.isBefore(state.bgn_dt())) {
            throw ApiException.badRequest("투입일(" + state.bgn_dt() + ")보다 앞선 날짜는 넣을 수 없습니다.", "aply_dt");
        }
        var closed = closed();
        reason(type, application, input.rsn_cd(), input.rsn_desc(), closed);
        String before;
        String after;
        var extra = new ArrayList<Warning>();
        switch (type) {
            case EXTEND -> {
                if (input.end_dt() == null || !input.end_dt().isAfter(state.end_dt())) {
                    throw ApiException.badRequest("새 종료일은 현재 종료일(" + state.end_dt() + ")보다 늦어야 합니다.", "end_dt");
                }
                if (!application.equals(state.end_dt().plusDays(1))) throw AssignmentChangeInput.error("aply_dt");
                before = state.end_dt().toString();
                after = input.end_dt().toString();
                changePeriod(state, state.bgn_dt(), input.end_dt(), state.asgn_se_cd(), state.cntrct_dev_id());
                dispatchWarning(state, input.end_dt(), extra);
                if (state.rsgn_dt() != null && input.end_dt().isAfter(state.rsgn_dt())) {
                    extra.add(new Warning("AFTER_RESIGNATION", "퇴사일 뒤로 연장합니다.", null));
                }
                rebuild(id);
            }
            case EARLY_END -> {
                if (application.equals(state.end_dt())) throw ApiException.badRequest("현재 종료일과 같습니다.", "aply_dt");
                if (!application.isBefore(state.end_dt())) throw AssignmentChangeInput.error("aply_dt");
                before = state.end_dt().toString();
                after = application.toString();
                changePeriod(state, state.bgn_dt(), application, state.asgn_se_cd(), state.cntrct_dev_id());
                rebuild(id);
            }
            case RATE -> {
                if (!Set.of("MO", "DP").contains(state.cntrct_ty_cd())) throw AssignmentChangeInput.error("bill_untprc_amt");
                YearMonth month;
                try { month = YearMonth.parse(input.work_ym()); }
                catch (RuntimeException e) { throw AssignmentChangeInput.error("work_ym"); }
                if (month.isBefore(YearMonth.from(state.bgn_dt())) || month.isAfter(YearMonth.from(state.end_dt()))) {
                    throw AssignmentChangeInput.error("work_ym");
                }
                LocalDate expected = month.atDay(1).isBefore(state.bgn_dt()) ? state.bgn_dt() : month.atDay(1);
                if (!application.equals(expected)) throw AssignmentChangeInput.error("aply_dt");
                Long current = mapper.rates(id).stream().filter(r -> !r.aply_bgn_dt().isAfter(application))
                    .max(Comparator.comparing(RateRow::aply_bgn_dt)).map(RateRow::bill_untprc_amt).orElse(null);
                if (input.bill_untprc_amt() == null || input.bill_untprc_amt() < 1) throw AssignmentChangeInput.error("bill_untprc_amt");
                if (Objects.equals(current, input.bill_untprc_amt())) throw ApiException.badRequest("현재 값과 같습니다.", "bill_untprc_amt");
                before = money(current);
                after = money(input.bill_untprc_amt());
                LocalDate financial = financialDate(application, period(state), closed);
                if (financial != null) mapper.rate(new RateWrite(id, financial, input.bill_untprc_amt(), input.rsn_desc(), user.userId()));
            }
            case EXT_CHANCE -> {
                if (Objects.equals(state.ext_psblty_cd(), input.ext_psblty_cd())) {
                    throw ApiException.badRequest("현재 값과 같습니다.", "ext_psblty_cd");
                }
                before = code("EXT_POSSIBILITY", state.ext_psblty_cd());
                after = code("EXT_POSSIBILITY", input.ext_psblty_cd());
                mapper.chance(new ChanceWrite(id, input.ext_psblty_cd()));
            }
            case PERIOD -> {
                LocalDate start = input.bgn_dt() == null ? state.bgn_dt() : input.bgn_dt();
                LocalDate end = input.end_dt() == null ? state.end_dt() : input.end_dt();
                String kind = input.asgn_se_cd();
                code("ASSIGN_KIND", kind);
                Long person = "SUB".equals(kind) ? input.cntrct_dev_id() : null;
                if ("SUB".equals(kind) && person == null && "SUB".equals(state.asgn_se_cd())) person = state.cntrct_dev_id();
                if ("SUB".equals(kind) && (person == null || mapper.developer(person) == null)) throw AssignmentChangeInput.error("cntrct_dev_id");
                if (state.rplc_asgn_id() != null && (!"SUB".equals(kind) || !Objects.equals(person, state.cntrct_dev_id()))) {
                    throw AssignmentChangeInput.error("cntrct_dev_id");
                }
                if (start.equals(state.bgn_dt()) && end.equals(state.end_dt()) && Objects.equals(kind, state.asgn_se_cd())
                        && Objects.equals(person, state.cntrct_dev_id())) {
                    throw ApiException.badRequest("바뀐 내용이 없습니다.", "asgn_se_cd");
                }
                if (!end.equals(state.end_dt()) && input.rsn_cd() == null) throw ApiException.badRequest("사유를 골라 주세요.", "rsn_cd");
                if (!start.equals(state.bgn_dt()) && (closed.contains(YearMonth.from(start)) || closed.contains(YearMonth.from(state.bgn_dt())))) {
                    throw ApiException.conflict("CLOSED_MONTH", "마감된 시작월은 바꿀 수 없습니다.", "bgn_dt");
                }
                before = periodText(state.bgn_dt(), state.end_dt(), state.asgn_se_cd(), state.cntrct_dev_id());
                after = periodText(start, end, kind, person);
                changePeriod(state, start, end, kind, person);
                if (!start.equals(state.bgn_dt())) {
                    var rates = mapper.rates(id);
                    if (!rates.isEmpty() && !closed.contains(YearMonth.from(rates.get(0).aply_bgn_dt()))) {
                        if (rates.stream().skip(1).anyMatch(r -> r.aply_bgn_dt().equals(start))) {
                            throw ApiException.conflict("RATE_DATE_CONFLICT", "새 투입일에 단가 이력이 있습니다.", "bgn_dt");
                        }
                        mapper.moveRate(new RateMove(id, rates.get(0).aply_bgn_dt(), start));
                    }
                }
                dispatchWarning(state, end, extra);
                rebuild(id);
            }
            default -> throw AssignmentChangeInput.error("asgn_chg_ty_cd");
        }
        if (retroactive(application, clock)) extra.add(new Warning("RETROACTIVE", "소급 입력입니다.", null));
        if (lastClosed(closed) != null && !YearMonth.from(application).isAfter(lastClosed(closed))) {
            extra.add(new Warning("CLOSED_MONTH", "마감월 숫자는 바뀌지 않고 첫 미마감월부터 반영합니다.", null));
        }
        long key = mapper.insert(new HistoryWrite(id, type.name(), application, before, after, input.rsn_cd(),
            input.rsn_desc(), retroactive(application, clock) ? "Y" : "N", null, user.userId()));
        return new SaveResult(detail(id, extra), row(id, key));
    }
    private void changePeriod(State state, LocalDate start, LocalDate end, String kind, Long person) {
        new Period(start, end, state.asgn_rt());
        var write = new PeriodWrite(state.asgn_id(), start, end, kind, person);
        if (mapper.overlap(write) > 0) throw ApiException.conflict("OVERLAP_ASSIGNMENT", "같은 프로젝트에 투입 기간이 겹칩니다.", null);
        mapper.period(write);
    }
    private void dispatchWarning(State state, LocalDate end, List<Warning> warnings) {
        if (!"DP".equals(state.cntrct_ty_cd())) return;
        Integer max = mapper.dispatchMonths(YearMonth.from(end).toString());
        LocalDate first = mapper.firstStart(state.asgn_id());
        if (max != null && first != null && !end.isBefore(first.plusMonths(max))) {
            warnings.add(new Warning("DISPATCH_MAX_MONTHS", "파견 최대 기간(" + max + "개월)을 넘습니다.", null));
        }
    }
    @Transactional
    public FixResult fix(long id, long chgId, FixInput input) {
        AssignmentActions.can(permissions, "createAssignmentFix");
        var initial = require(id);
        concurrency.lockProject(initial.prj_id());
        var locked = concurrency.lockAndCompare(id, input.updated_at());
        var state = require(id);
        writable(state, Mutation.FIX, null);
        var target = row(id, chgId);
        requireFixTarget(id, target.asgn_id(), ChangeType.valueOf(target.asgn_chg_ty_cd()), "Y".equals(target.fixed_yn()));
        if (input.aft_val() == null || input.aft_val().isBlank()) throw ApiException.badRequest("바른 내용을 적어 주세요.", "aft_val");
        if (input.aft_val().length() > 200) throw AssignmentChangeInput.error("aft_val");
        reason(ChangeType.FIX, target.aply_dt(), input.rsn_cd(), input.rsn_desc(), closed());
        if (mapper.markFixed(new ChangeKey(id, chgId)) != 1) throw ApiException.conflict("NOT_FIXABLE", "정정할 수 없는 이력입니다.", null);
        long key = mapper.insert(new HistoryWrite(id, "FIX", target.aply_dt(), target.aft_val(), input.aft_val(),
            input.rsn_cd(), input.rsn_desc(), retroactive(target.aply_dt(), clock) ? "Y" : "N", chgId, user.userId()));
        concurrency.advance(locked);
        return new FixResult(detail(id, new ArrayList<>()), row(id, key), row(id, chgId));
    }
}
