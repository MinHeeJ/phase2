package com.sicost.dispatchattendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.*;
import java.time.YearMonth;
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
    private Project project(Scope scope, boolean write) {
        var project = write ? mapper.lockProject(scope.projectId()) : mapper.project(scope.projectId());
        if (project == null || !"DP".equals(project.cntrct_ty_cd())
                || !Set.of("DAY", "HOUR").contains(Objects.toString(project.stlm_mthd_cd(), "")))
            throw BillingValues.invalid("projectId");
        return project;
    }
    public AttendanceResult get(Scope scope) {
        permissions.can(BillingActions.READ);
        project(scope, false);
        return result(scope);
    }
    public static BigDecimal calculatedMm(BigDecimal days, BigDecimal denominator) {
        return days == null || denominator == null || denominator.signum() <= 0
            ? null : days.divide(denominator, 2, RoundingMode.HALF_UP);
    }
    private AttendanceResult result(Scope scope) {
        List<String> warnings = new ArrayList<>();
        if ("CLOSED".equals(mapper.monthStatus(scope.month()))) warnings.add("마감된 달입니다. 읽기 전용입니다.");
        List<AttendanceRow> rows = mapper.sources(scope).stream().map(source -> {
            List<String> notes = new ArrayList<>();
            if (source.mth_work_day_qty() == null) notes.add("근태 미입력: 청구 자동 상세에서 제외됩니다.");
            if (!source.price_available()) notes.add("단가 미입력: 근태 저장은 가능하나 청구 생성은 차단됩니다.");
            if (source.assignment_count() > 1) notes.add("동일 인력의 배정이 중복됩니다. 배정을 명시적으로 정리해 주세요.");
            if (source.billed()) notes.add("확정 청구 연결 근태입니다. 수정할 수 없습니다.");
            BigDecimal mm = calculatedMm(source.work_day_qty(), source.mth_work_day_qty());
            if (source.work_day_qty() == null) notes.add("근무일수 미입력: 산출 MM이 없습니다.");
            if (mm != null && source.assignment_mm() != null && mm.compareTo(source.assignment_mm()) != 0)
                notes.add("산출 MM과 ⑦ 투입 MM이 다릅니다. 원본 MM은 변경하지 않습니다.");
            return new AttendanceRow(source.asgn_id(), source.dev_id(), source.work_day_qty(), source.rglr_tm_qty(),
                source.ovtm_tm_qty(), source.mth_work_day_qty(), mm, source.assignment_mm(), source.price_available(), List.copyOf(notes));
        }).toList();
        return new AttendanceResult(scope.month(), scope.projectId(), rows, List.copyOf(warnings));
    }
    @Transactional
    public AttendanceResult save(Scope scope, List<AttendanceInput> inputs) {
        permissions.can(BillingActions.UPDATE);
        var project = project(scope, true);
        BillingRules.requireOpen(YearMonth.from(scope.firstDay()), mapper.monthStatus(scope.month()), "month");
        var before = mapper.lockAttendance(scope);
        var sources = mapper.sources(scope);
        Map<Long, Source> byAssignment = new HashMap<>();
        sources.forEach(row -> byAssignment.put(row.asgn_id(), row));
        Set<Long> people = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            String prefix = "items[" + i + "].";
            var source = byAssignment.get(input.asgn_id());
            if (source == null || source.dev_id() != input.dev_id() || source.assignment_count() != 1)
                throw BillingValues.invalid(prefix + "asgn_id");
            if (!people.add(input.dev_id())) throw BillingValues.invalid(prefix + "dev_id");
            if (input.mth_work_day_qty() == null || input.mth_work_day_qty().signum() <= 0
                    || input.mth_work_day_qty().compareTo(BigDecimal.valueOf(scope.lastDay().getDayOfMonth())) > 0)
                throw BillingValues.invalid(prefix + "mth_work_day_qty");
            if ("DAY".equals(project.stlm_mthd_cd()) && input.work_day_qty() == null)
                throw BillingValues.invalid(prefix + "work_day_qty");
            if ("HOUR".equals(project.stlm_mthd_cd()) && input.rglr_tm_qty() == null)
                throw BillingValues.invalid(prefix + "rglr_tm_qty");
            if (source.billed()) throw ApiException.conflict("ATTENDANCE_BILLED", "확정 청구에 연결된 근태는 수정할 수 없습니다.", prefix + "asgn_id");
        }
        Map<Long, Stored> oldRows = new HashMap<>();
        before.forEach(row -> oldRows.put(row.dev_id(), row));
        for (var input : inputs.stream().sorted(Comparator.comparingLong(AttendanceInput::dev_id)).toList()) {
            var old = oldRows.get(input.dev_id());
            long id = mapper.upsert(new AttendanceWrite(scope, input, user.userId()));
            var after = new Stored(id, input.asgn_id(), input.dev_id(), input.work_day_qty(), input.rglr_tm_qty(),
                input.ovtm_tm_qty(), input.mth_work_day_qty(), null);
            mapper.audit(new AttendanceAudit(Long.toString(id), old == null ? "INSERT" : "UPDATE",
                snapshot(old), snapshot(after), user.userId()));
        }
        return result(scope);
    }
    private String snapshot(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
