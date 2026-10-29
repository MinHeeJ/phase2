package com.sicost.dispatchattendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.billing.BillingMutationService;
import com.sicost.common.billing.BillingRules;
import com.sicost.common.billing.BillingSupportModels.Audit;
import com.sicost.common.security.BillingActions;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;

@Service
@Transactional(readOnly = true)
public class DispatchAttendanceService {
    private final DispatchAttendanceMapper mapper;
    private final BillingMutationService mutations;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;

    public DispatchAttendanceService(DispatchAttendanceMapper mapper, BillingMutationService mutations,
            CurrentUser user, PermissionService permissions, ObjectMapper json) {
        this.mapper = mapper;
        this.mutations = mutations;
        this.user = user;
        this.permissions = permissions;
        this.json = json;
    }
    private Scope scope(String month, long projectId) {
        var period = BillingRules.month(month, "month");
        if (projectId < 1) throw BillingRules.invalid("projectId");
        return new Scope(projectId, month, period.atDay(1), period.atEndOfMonth());
    }
    private Project project(Scope scope) {
        var project = mapper.project(scope.projectId());
        if (project == null || !"DP".equals(project.cntrct_ty_cd()) || project.stlm_mthd_cd() == null
                || !Set.of("DAY", "HOUR").contains(project.stlm_mthd_cd())) {
            throw BillingRules.invalid("projectId");
        }
        return project;
    }
    public Result getDispatchAttendance(String month, long projectId) {
        BillingActions.can(permissions, "getDispatchAttendance");
        var scope = scope(month, projectId);
        project(scope);
        return result(scope);
    }
    private Result result(Scope scope) {
        var rows = mapper.list(scope).stream().map(source -> {
            var warnings = new ArrayList<String>();
            var denominator = source.mth_work_day_qty();
            BigDecimal mm = null;
            if (denominator == null || denominator.signum() <= 0) warnings.add("근태 미입력: 청구 자동 상세에서 제외됩니다.");
            else if (source.work_day_qty() == null) warnings.add("근무일수 미입력으로 산출 MM이 없습니다. 근무일수를 추정하지 않습니다.");
            else mm = source.work_day_qty().divide(denominator, 2, RoundingMode.HALF_UP);
            if (mm != null && source.assignment_mm() != null && mm.compareTo(source.assignment_mm()) != 0) {
                warnings.add("산출 MM과 ⑦ 투입 MM이 다릅니다. 원본 투입 MM은 변경하지 않습니다.");
            }
            if (!source.price_available()) warnings.add("단가 누락: 근태 저장은 가능하지만 청구 자동 생성은 불가합니다.");
            return new Row(source.asgn_id(), source.dev_id(), source.work_day_qty(), source.rglr_tm_qty(),
                source.ovtm_tm_qty(), denominator, mm, source.assignment_mm(), source.price_available(),
                List.copyOf(warnings));
        }).toList();
        return new Result(scope.month(), scope.projectId(), rows,
            rows.stream().flatMap(row -> row.warnings().stream()).distinct().toList());
    }
    private BigDecimal quantity(BigDecimal value, int precision, String field) {
        return value == null ? null : BillingRules.quantity(value, precision, 1, field);
    }
    private Input validate(Input row, Project project, Scope scope, String prefix) {
        if (row == null || row.asgn_id() < 1) throw BillingRules.invalid(prefix + "asgn_id");
        if (row.dev_id() < 1) throw BillingRules.invalid(prefix + "dev_id");
        var days = quantity(row.work_day_qty(), 5, prefix + "work_day_qty");
        var regular = quantity(row.rglr_tm_qty(), 6, prefix + "rglr_tm_qty");
        var overtime = quantity(row.ovtm_tm_qty(), 6, prefix + "ovtm_tm_qty");
        var denominator = BillingRules.quantity(row.mth_work_day_qty(), 5, 1, prefix + "mth_work_day_qty");
        if (denominator.signum() <= 0 || denominator.compareTo(BigDecimal.valueOf(scope.end().getDayOfMonth())) > 0) {
            throw BillingRules.invalid(prefix + "mth_work_day_qty");
        }
        if ("DAY".equals(project.stlm_mthd_cd()) && days == null) throw BillingRules.invalid(prefix + "work_day_qty");
        if ("HOUR".equals(project.stlm_mthd_cd()) && regular == null) throw BillingRules.invalid(prefix + "rglr_tm_qty");
        if (days != null && days.divide(denominator, 2, RoundingMode.HALF_UP).compareTo(new BigDecimal("999.99")) > 0) {
            throw BillingRules.invalid(prefix + "work_day_qty");
        }
        return new Input(row.asgn_id(), row.dev_id(), days, regular,
            overtime == null ? BigDecimal.ZERO : overtime, denominator);
    }
    @Transactional
    public Result saveDispatchAttendance(String month, long projectId, List<Input> inputs) {
        BillingActions.can(permissions, "saveDispatchAttendance");
        var scope = scope(month, projectId);
        mutations.lockProject(projectId);
        var project = project(scope);
        mutations.requireOpen(BillingRules.month(month, "month"), "month");
        if (inputs == null) throw BillingRules.invalid("items");
        var sources = mapper.list(scope);
        var rows = new ArrayList<Input>();
        var used = new HashSet<Long>();
        for (int i = 0; i < inputs.size(); i++) {
            String prefix = "items[" + i + "].";
            var row = validate(inputs.get(i), project, scope, prefix);
            if (!used.add(row.dev_id())) throw BillingRules.invalid(prefix + "dev_id");
            if (sources.stream().noneMatch(s -> s.asgn_id() == row.asgn_id() && s.dev_id() == row.dev_id())) {
                throw BillingRules.invalid(prefix + "asgn_id");
            }
            var key = new Key(scope, row.dev_id());
            if (mapper.billed(key)) {
                throw ApiException.conflict("ATTENDANCE_BILLED", "확정 청구에 연결된 근태는 수정할 수 없습니다.", prefix + "asgn_id");
            }
            rows.add(row);
        }
        for (var row : rows) {
            var before = mapper.existing(new Key(scope, row.dev_id()));
            Long id = mapper.upsert(new Write(scope, row, user.userId()));
            if (id == null) throw new IllegalStateException("근태 저장 결과가 없습니다.");
            mutations.audit(new Audit("bill.dspch_atnd", id.toString(), before.isEmpty() ? "INSERT" : "UPDATE",
                "attendance", snapshot(before.isEmpty() ? null : before.get(0)), snapshot(row), "파견 근태 수동 저장", null));
        }
        return result(scope);
    }
    private String snapshot(Input row) {
        if (row == null) return null;
        try { return json.writeValueAsString(row); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("근태 변경 기록을 저장할 수 없습니다.", ex); }
    }
}
