package com.sicost.assignments;

import com.sicost.common.api.ApiException;
import com.sicost.common.persistence.AssignmentConcurrencyService;
import com.sicost.common.security.*;
import com.sicost.common.validation.AssignmentRules;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.assignments.AssignmentModels.*;

@Service
@Transactional(readOnly = true)
public class AssignmentService {
    private final AssignmentMapper mapper;
    private final AssignmentConcurrencyService concurrency;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public AssignmentService(AssignmentMapper mapper, AssignmentConcurrencyService concurrency,
            CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper = mapper; this.concurrency = concurrency; this.user = user;
        this.permissions = permissions; this.clock = clock;
    }
    public Page list(Query query) {
        AssignmentActions.can(permissions, "listAssignments");
        var rows = mapper.list(query).stream().map(this::item)
            .filter(i -> query.warning() == null || i.warnings().stream().anyMatch(w -> w.code().equals(query.warning())))
            .sorted(order(query.sort())).toList();
        return new Page(rows.stream().skip((long) query.page() * query.size()).limit(query.size()).toList(),
            rows.size(), query.page(), query.size());
    }
    private Comparator<Item> order(String sort) {
        Comparator<Item> result = (a, b) -> 0;
        for (String part : sort.split(";")) {
            String[] pair = part.split(",");
            Comparator<Item> next = Comparator.comparing(i -> sortable(i.row(), pair[0]),
                Comparator.nullsLast(Comparator.naturalOrder()));
            result = result.thenComparing(pair[1].equals("desc") ? next.reversed() : next);
        }
        return result.thenComparingLong(i -> i.row().asgn_id());
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Comparable sortable(Row row, String key) {
        return switch (key) {
            case "asgn_no" -> row.asgn_no(); case "prj_nm" -> row.prj_nm(); case "dev_nm" -> row.dev_nm();
            case "job_cd" -> row.job_cd(); case "bgn_dt" -> row.bgn_dt(); case "end_dt" -> row.end_dt();
            case "bill_untprc_amt" -> row.bill_untprc_amt(); case "current_month_mm_qty" -> row.current_month_mm_qty();
            case "year_mm_qty" -> row.year_mm_qty(); case "ext_psblty_cd" -> row.ext_psblty_cd();
            default -> row.asgn_se_cd();
        };
    }
    public Detail get(long id) {
        AssignmentActions.can(permissions, "getAssignment");
        if (id < 1) throw AssignmentInput.error("id");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        Row row = mapper.row(new ReadKey(id, today.getYear(), today, YearMonth.from(today).toString()));
        if (row == null) throw ApiException.notFound("투입이 없습니다.");
        return new Detail(item(row), row.rmrk_desc(), row.extend_count(), mapper.rates(id), mapper.months(id));
    }
    private Item item(Row row) {
        var warnings = new ArrayList<Warning>();
        if (row.over_mm()) warnings.add(new Warning("OVER_MM", "MM 1.0 초과", null));
        if (row.pre_contract()) warnings.add(new Warning("PRE_CONTRACT", "미계약 선투입", null));
        if (row.on_leave()) warnings.add(new Warning("ON_LEAVE", "휴직 기간 배정", null));
        if (row.dispatch_nonreg()) warnings.add(new Warning("DISPATCH_NONREG", "파견에 프리랜서·외주 배정", null));
        return new Item(row, warnings);
    }
    @Transactional
    public SaveResult create(CreateInput input) {
        AssignmentActions.can(permissions, "createAssignment");
        concurrency.lockProject(input.prj_id());
        Project project = mapper.project(input.prj_id());
        AssignmentRules.requireProjectWritable(project.use_yn(), project.prj_stts_cd());
        var dev = mapper.developer(input.dev_id());
        if (dev == null) throw ApiException.notFound("개발자가 없습니다.");
        if (!"Y".equals(dev.use_yn()) || (dev.rsgn_dt() != null && !dev.rsgn_dt().isAfter(LocalDate.now(clock))))
            throw AssignmentInput.error("dev_id");
        if (!mapper.activeCode(new CodeKey("JOB", input.job_cd()))) throw AssignmentInput.error("job_cd");
        if (input.cntrct_dev_id() != null && mapper.developer(input.cntrct_dev_id()) == null)
            throw AssignmentInput.error("cntrct_dev_id");
        var period = new AssignmentRules.Period(input.bgn_dt(), input.end_dt(), input.asgn_rt());
        var pricing = AssignmentRules.pricing(project.cntrct_ty_cd(), input.bill_untprc_amt(), input.untprc_base_cd());
        Set<YearMonth> closed = mapper.closedMonths().stream().map(YearMonth::parse).collect(Collectors.toSet());
        AssignmentRules.requireReason(AssignmentRules.ChangeType.START, input.bgn_dt(), clock,
            closed.stream().max(Comparator.naturalOrder()).orElse(null),
            new AssignmentRules.Reason(input.rsn_cd(), input.rsn_desc(), input.rsn_cd() != null
                && mapper.activeCode(new CodeKey("ASGN_CHG_RSN", input.rsn_cd()))));
        String overlap = mapper.overlap(input);
        if (overlap != null) throw ApiException.conflict("OVERLAP_ASSIGNMENT",
            "같은 프로젝트에 기간이 겹치는 투입이 있습니다(" + overlap + ").", null);
        long id = mapper.nextId();
        mapper.insert(new Write(id, AssignmentRules.allocated(id), input, pricing.storageBasis(), user.userId()));
        LocalDate effective = AssignmentRules.financialDate(input.bgn_dt(), period, closed);
        if (effective != null) mapper.rate(new RateWrite(id, effective, pricing.amount(), user.userId()));
        for (var month : AssignmentRules.rebuildMonths(period, List.of(), closed)) {
            mapper.month(new MonthWrite(id, month.month().toString(), month.quantity(), user.userId()));
        }
        String display = input.bgn_dt() + " ~ " + input.end_dt() + " · " + input.asgn_rt().toPlainString();
        long change = mapper.start(new StartWrite(id, input, display,
            AssignmentRules.retroactive(input.bgn_dt(), clock) ? "Y" : "N", user.userId()));
        return new SaveResult(get(id), mapper.change(change));
    }
}
