package com.sicost.months;

import com.sicost.common.api.ApiException;
import com.sicost.common.persistence.AssignmentConcurrencyService;
import com.sicost.common.security.*;
import com.sicost.common.validation.AssignmentRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.months.AssignmentMonthsModels.*;

@Service
@Transactional(readOnly = true)
public class AssignmentMonthsService {
    private final AssignmentMonthsMapper mapper;
    private final AssignmentConcurrencyService concurrency;
    private final CurrentUser user;
    private final PermissionService permissions;

    public AssignmentMonthsService(AssignmentMonthsMapper mapper, AssignmentConcurrencyService concurrency,
            CurrentUser user, PermissionService permissions) {
        this.mapper = mapper;
        this.concurrency = concurrency;
        this.user = user;
        this.permissions = permissions;
    }
    private AssignmentState require(long id) {
        if (id < 1) throw AssignmentMonthsInput.invalid("asgn_id");
        var row = mapper.assignment(id);
        if (row == null) throw ApiException.notFound("투입이 없습니다.");
        return row;
    }
    public MonthsResult get(long id) {
        AssignmentActions.can(permissions, "getAssignmentMonths");
        return result(require(id));
    }
    private MonthsResult result(AssignmentState assignment) {
        var rows = mapper.months(assignment.asgn_id());
        var warnings = new ArrayList<>(mapper.warnings(assignment.asgn_id()));
        for (var row : rows) {
            if (row.person_month_mm_qty().compareTo(BigDecimal.ONE) > 0) {
                warnings.add(new Warning("OVER_MM", "MM 1.0 초과", row.work_ym()));
            }
        }
        return new MonthsResult(assignment.asgn_id(), assignment.updated_at(), rows, List.copyOf(warnings));
    }
    @Transactional
    public MonthsResult save(long id, MonthsInput input) {
        AssignmentActions.can(permissions, "saveAssignmentMonths");
        if (input == null || input.months() == null) throw AssignmentMonthsInput.invalid("months");
        var locked = concurrency.lockAndCompare(id, input.updated_at());
        var assignment = require(id);
        AssignmentRules.requireProjectWritable(assignment.use_yn(), assignment.prj_stts_cd());
        AssignmentRules.requireMutation(assignment.asgn_stts_cd(), AssignmentRules.Mutation.MONTHS,
            assignment.bgn_dt(), assignment.end_dt());
        if (mapper.overlaps(id)) {
            throw ApiException.conflict("OVERLAP_ASSIGNMENT", "같은 사람의 프로젝트 투입 기간이 겹칩니다.", null);
        }
        var current = new HashMap<String, MonthRow>();
        mapper.months(id).forEach(row -> current.put(row.work_ym(), row));
        var changes = new ArrayList<MonthWrite>();
        var seen = new HashSet<String>();
        for (var item : input.months()) {
            if (item == null || !seen.add(item.work_ym())) throw AssignmentMonthsInput.invalid("work_ym");
            var before = current.get(item.work_ym());
            if (before == null) throw AssignmentMonthsInput.invalid("work_ym");
            if ("Y".equals(before.closed_yn())) {
                throw ApiException.conflict("CLOSED_MONTH", "마감된 달의 MM은 바꿀 수 없습니다.", "work_ym");
            }
            LocalDate effective = LocalDate.parse(item.work_ym() + "-01");
            if (effective.isBefore(assignment.bgn_dt())) effective = assignment.bgn_dt();
            AssignmentRules.requireMutation(assignment.asgn_stts_cd(), AssignmentRules.Mutation.MONTHS,
                effective, assignment.end_dt());
            var quantity = AssignmentMonthsInput.quantity(item.mm_qty());
            String manual = quantity.compareTo(before.auto_mm_qty()) == 0 ? "N" : "Y";
            if (quantity.compareTo(before.mm_qty()) != 0 || !manual.equals(before.mnl_yn())) {
                changes.add(new MonthWrite(id, item.work_ym(), quantity, manual, user.userId()));
            }
        }
        for (var change : changes) {
            if (mapper.update(change) != 1) {
                throw ApiException.conflict("CLOSED_MONTH", "마감된 달의 MM은 바꿀 수 없습니다.", "work_ym");
            }
            mapper.audit(new MonthAudit(Long.toString(id), change.month(),
                current.get(change.month()).mm_qty().setScale(2).toPlainString(),
                change.quantity().toPlainString(), user.userId()));
        }
        concurrency.advance(locked);
        return result(require(id));
    }
}
