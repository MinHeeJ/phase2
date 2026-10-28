package com.sicost.dispatchattendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.BillingRules;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

@Service
@Transactional(readOnly = true)
public class DispatchAttendanceService {
    private final DispatchAttendanceMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;

    public DispatchAttendanceService(DispatchAttendanceMapper mapper, CurrentUser user,
            PermissionService permissions, ObjectMapper json) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
        this.json = json;
    }
    private Project require(Scope scope, boolean writing) {
        var project = writing ? mapper.lockProject(scope.projectId()) : mapper.project(scope.projectId());
        if (project == null || !"DP".equals(project.type())
                || !Set.of("DAY", "HOUR").contains(Objects.toString(project.method(), ""))
                || (writing && !"Y".equals(project.enabled()))) {
            throw BillingRules.invalid("projectId");
        }
        return project;
    }
    public Result getDispatchAttendance(String month, Long projectId) {
        BillingActions.can(permissions, "getDispatchAttendance");
        var scope = DispatchAttendanceInput.scope(month, projectId);
        require(scope, false);
        return result(scope, mapper.assignments(scope), mapper.attendance(scope));
    }
    @Transactional
    public Result saveDispatchAttendance(String month, Long projectId, List<Input> items) {
        BillingActions.can(permissions, "saveDispatchAttendance");
        var scope = DispatchAttendanceInput.scope(month, projectId);
        var project = require(scope, true);
        BillingRules.openMonth(month, mapper.monthStatus(month));
        var existing = mapper.lockAttendance(scope);
        var assignments = mapper.assignments(scope);
        var confirmed = new HashSet<>(mapper.confirmedAssignments(scope));
        if (items == null) throw BillingRules.invalid("items");
        var developers = new HashSet<Long>();
        var writes = new ArrayList<Input>();
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            String field = "items[" + index + "].";
            if (item == null) throw BillingRules.invalid(field);
            var assignment = assignments.stream().filter(a -> a.asgn_id() == item.asgn_id()
                && a.dev_id() == item.dev_id()).findFirst();
            if (assignment.isEmpty()) throw BillingRules.invalid(field + "asgn_id");
            if (!developers.add(item.dev_id())) throw BillingRules.invalid(field + "dev_id");
            var before = existing.stream().filter(s -> s.dev_id() == item.dev_id()).findFirst().orElse(null);
            if (confirmed.contains(item.asgn_id()) || (before != null && confirmed.contains(before.asgn_id()))) {
                throw ApiException.conflict("ATTENDANCE_BILLED", "확정 청구에 연결된 근태는 변경할 수 없습니다.", field + "asgn_id");
            }
            BigDecimal denominator = item.mth_work_day_qty();
            if (denominator == null || denominator.signum() <= 0
                    || denominator.compareTo(BigDecimal.valueOf(scope.last().getDayOfMonth())) > 0) {
                throw BillingRules.invalid(field + "mth_work_day_qty");
            }
            if (project.method().equals("DAY") && item.work_day_qty() == null) {
                throw BillingRules.invalid(field + "work_day_qty");
            }
            if (project.method().equals("HOUR") && item.rglr_tm_qty() == null) {
                throw BillingRules.invalid(field + "rglr_tm_qty");
            }
            BigDecimal days = item.work_day_qty() != null ? item.work_day_qty()
                : before == null ? null : before.work_day_qty();
            BigDecimal regular = item.rglr_tm_qty() != null ? item.rglr_tm_qty()
                : before == null ? null : before.rglr_tm_qty();
            BigDecimal overtime = item.ovtm_tm_qty() != null ? item.ovtm_tm_qty()
                : before == null || before.ovtm_tm_qty() == null ? BigDecimal.ZERO : before.ovtm_tm_qty();
            validate(days, 5, field + "work_day_qty");
            validate(regular, 6, field + "rglr_tm_qty");
            validate(overtime, 6, field + "ovtm_tm_qty");
            validate(denominator, 5, field + "mth_work_day_qty");
            if (days != null) BillingRules.attendanceMm(days, denominator);
            writes.add(new Input(item.asgn_id(), item.dev_id(), days, regular, overtime, denominator));
        }
        for (var item : writes) {
            var before = existing.stream().filter(s -> s.dev_id() == item.dev_id()).findFirst().orElse(null);
            long id = mapper.upsert(new Write(scope, item, user.userId()));
            var after = new Stored(id, item.asgn_id(), item.dev_id(), item.work_day_qty(),
                item.rglr_tm_qty(), item.ovtm_tm_qty(), item.mth_work_day_qty());
            mapper.audit(new Audit(Long.toString(id), before == null ? "INSERT" : "UPDATE",
                snapshot(before), snapshot(after), user.userId()));
        }
        return result(scope, assignments, mapper.attendance(scope));
    }
    private static void validate(BigDecimal value, int precision, String field) {
        if (value == null) return;
        try {
            if (value.signum() < 0 || value.setScale(1, java.math.RoundingMode.UNNECESSARY).precision() > precision) {
                throw BillingRules.invalid(field);
            }
        } catch (ArithmeticException ex) { throw BillingRules.invalid(field); }
    }
    private Result result(Scope scope, List<Assignment> assignments, List<Stored> attendance) {
        var rows = new ArrayList<Row>();
        var warnings = new LinkedHashSet<String>();
        for (var assignment : assignments) {
            var stored = attendance.stream().filter(s -> Objects.equals(s.asgn_id(), assignment.asgn_id())
                && s.dev_id() == assignment.dev_id()).findFirst().orElse(null);
            BigDecimal days = stored == null ? null : stored.work_day_qty();
            BigDecimal denominator = stored == null ? null : stored.mth_work_day_qty();
            BigDecimal mm = days == null || denominator == null || denominator.signum() <= 0
                ? null : BillingRules.attendanceMm(days, denominator);
            var messages = new ArrayList<String>();
            if (stored == null) messages.add("근태 미입력: 자동 청구 상세에서 제외됩니다.");
            if (!assignment.price_available()) messages.add("단가 미입력: 근태 저장은 가능하나 청구 생성은 불가합니다.");
            if (mm == null) messages.add("근무일수 또는 해당 월 근무일수가 없어 산출 MM을 계산하지 않습니다.");
            if (mm != null && assignment.assignment_mm() != null && mm.compareTo(assignment.assignment_mm()) != 0) {
                messages.add("산출 MM과 ⑦ 투입 MM이 다릅니다. 원본 투입 MM은 변경하지 않습니다.");
            }
            warnings.addAll(messages);
            rows.add(new Row(assignment.asgn_id(), assignment.dev_id(), days,
                stored == null ? null : stored.rglr_tm_qty(), stored == null ? null : stored.ovtm_tm_qty(),
                denominator, mm, assignment.assignment_mm(), assignment.price_available(), List.copyOf(messages)));
        }
        return new Result(scope.month(), scope.projectId(), List.copyOf(rows), List.copyOf(warnings));
    }
    private String snapshot(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("근태 이력을 저장할 수 없습니다.", ex); }
    }
}
