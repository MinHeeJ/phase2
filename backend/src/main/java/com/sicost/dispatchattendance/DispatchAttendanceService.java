package com.sicost.dispatchattendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
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
    private final Clock clock;
    private final ObjectMapper json;

    public DispatchAttendanceService(DispatchAttendanceMapper mapper, CurrentUser user,
            PermissionService permissions, Clock clock, ObjectMapper json) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
        this.json = json;
    }
    private Scope scope(String month, Long projectId) {
        var ym = BillingValues.month(month, "month");
        if (projectId == null || projectId < 1) throw BillingValues.invalid("projectId");
        return new Scope(month, projectId, ym.atDay(1), ym.atEndOfMonth());
    }
    private Project project(Scope scope, boolean lock) {
        var row = lock ? mapper.lockProject(scope.projectId()) : mapper.project(scope.projectId());
        if (row == null || !"DP".equals(row.type())
                || !Set.of("DAY", "HOUR").contains(Objects.toString(row.method(), ""))) {
            throw BillingValues.invalid("projectId");
        }
        if (lock && !"Y".equals(row.enabled())) throw BillingValues.invalid("projectId");
        return row;
    }
    private void permission(String operation) {
        if (!BillingActions.can(permissions, operation)) {
            throw new ApiException(403, "FORBIDDEN", "이 작업을 사용할 수 없습니다.", null);
        }
    }
    public Result get(String month, Long projectId) {
        permission("getDispatchAttendance");
        var scope = scope(month, projectId);
        project(scope, false);
        return result(scope);
    }
    @Transactional
    public Result save(String month, Long projectId, Batch batch) {
        permission("saveDispatchAttendance");
        var scope = scope(month, projectId);
        var project = project(scope, true);
        if (BillingRules.pastYear(scope.first().getYear(), clock)) throw BillingValues.invalid("month");
        if ("CLOSED".equals(mapper.monthStatus(month))) {
            throw ApiException.conflict("CLOSED_MONTH", "마감된 달은 변경할 수 없습니다.", "month");
        }
        if (batch == null || batch.items() == null) throw BillingValues.invalid("items");
        var sources = mapper.sources(scope);
        var seen = new HashSet<Long>();
        var writes = new ArrayList<Write>();
        for (int i = 0; i < batch.items().size(); i++) {
            var item = batch.items().get(i);
            String prefix = "items[" + i + "].";
            if (item == null) throw BillingValues.invalid("items[" + i + "]");
            if (item.dev_id() == null || item.dev_id() < 1 || !seen.add(item.dev_id())) {
                throw BillingValues.invalid(prefix + "dev_id");
            }
            if (item.asgn_id() == null || sources.stream().noneMatch(s ->
                    item.asgn_id().equals(s.asgn_id()) && item.dev_id().equals(s.dev_id()))) {
                throw BillingValues.invalid(prefix + "asgn_id");
            }
            decimal(item.mth_work_day_qty(), true, 31, prefix + "mth_work_day_qty");
            if (item.mth_work_day_qty().signum() <= 0
                    || item.mth_work_day_qty().compareTo(BigDecimal.valueOf(scope.last().getDayOfMonth())) > 0) {
                throw BillingValues.invalid(prefix + "mth_work_day_qty");
            }
            decimal(item.work_day_qty(), "DAY".equals(project.method()), 9999, prefix + "work_day_qty");
            decimal(item.rglr_tm_qty(), "HOUR".equals(project.method()), 99999, prefix + "rglr_tm_qty");
            decimal(item.ovtm_tm_qty(), false, 99999, prefix + "ovtm_tm_qty");
            var normalized = new Input(item.asgn_id(), item.dev_id(), item.work_day_qty(),
                item.rglr_tm_qty(), item.ovtm_tm_qty() == null ? BigDecimal.ZERO : item.ovtm_tm_qty(),
                item.mth_work_day_qty());
            var write = new Write(scope, normalized, user.userId());
            if (mapper.billed(write)) {
                throw ApiException.conflict("ATTENDANCE_BILLED", "확정 청구에 연결된 근태입니다.", prefix + "asgn_id");
            }
            writes.add(write);
        }
        for (var write : writes) {
            var before = mapper.existing(write);
            Long id = mapper.upsert(write);
            mapper.audit(new Audit(Long.toString(id), before == null ? "INSERT" : "UPDATE",
                snapshot(before), snapshot(write.item()), user.userId()));
        }
        return result(scope);
    }
    private void decimal(BigDecimal value, boolean required, long max, String field) {
        if (value == null) {
            if (required) throw BillingValues.invalid(field);
            return;
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0
                || value.stripTrailingZeros().scale() > 1) throw BillingValues.invalid(field);
    }
    private Result result(Scope scope) {
        var rows = mapper.sources(scope).stream().map(source -> {
            var warnings = new ArrayList<String>();
            BigDecimal mm = null;
            if (source.mth_work_day_qty() == null || source.mth_work_day_qty().signum() <= 0) {
                warnings.add("근태 미입력: 청구 자동 상세에서 제외됩니다.");
            } else if (source.work_day_qty() != null) {
                mm = source.work_day_qty().divide(source.mth_work_day_qty(), 2, RoundingMode.HALF_UP);
            }
            if (!Boolean.TRUE.equals(source.price_available())) warnings.add("단가 미입력: 근태 저장은 가능하나 청구 생성은 불가합니다.");
            if (mm != null && source.assignment_mm() != null && mm.compareTo(source.assignment_mm()) != 0) {
                warnings.add("산출 MM과 ⑦ 투입 MM이 다릅니다. 원본 투입 MM은 변경하지 않습니다.");
            }
            return new Row(source.asgn_id(), source.dev_id(), source.work_day_qty(), source.rglr_tm_qty(),
                source.ovtm_tm_qty(), source.mth_work_day_qty(), mm, source.assignment_mm(),
                Boolean.TRUE.equals(source.price_available()), List.copyOf(warnings));
        }).toList();
        return new Result(scope.month(), scope.projectId(), rows,
            rows.stream().flatMap(row -> row.warnings().stream()).distinct().toList());
    }
    private String snapshot(Input input) {
        if (input == null) return null;
        try { return json.writeValueAsString(input); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("근태 변경 기록을 저장할 수 없습니다.", ex); }
    }
}
