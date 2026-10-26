package com.sicost.staffplans;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.CalendarValues;
import com.sicost.common.validation.ProjectValues;
import java.math.*;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.staffplans.ProjectStaffPlanModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectStaffPlanService {
    private final ProjectStaffPlanMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    public ProjectStaffPlanService(ProjectStaffPlanMapper mapper, CurrentUser user, PermissionService permissions) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
    }
    private StaffProject require(long id) {
        if (id < 1) throw ApiException.badRequest("프로젝트 번호를 확인하세요.", "id");
        var project = mapper.project(id);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        return project;
    }
    public StaffPlans getProjectStaffPlans(long id) {
        permissions.can(ProjectActions.key("getProjectStaffPlans"));
        require(id);
        return new StaffPlans(mapper.plans(id), mapper.actual(id));
    }
    private String key(String month, String job, String grade) { return month + ":" + job + ":" + grade; }
    private boolean unchanged(StaffPlan old, StaffPlanInput input) {
        return old != null && old.need_mm_qty().compareTo(input.need_mm_qty()) == 0
            && Objects.equals(old.expc_mth_cst_amt(), input.expc_mth_cst_amt());
    }
    private void code(String group, String value, String field, boolean retained) {
        if (!value.isEmpty() && !retained && !mapper.activeCode(new StaffCode(group, value))) {
            throw ApiException.badRequest("사용 중인 코드를 골라 주세요.", field);
        }
    }
    @Transactional
    public StaffPlans saveProjectStaffPlans(long id, List<StaffPlanInput> input) {
        permissions.can(ProjectActions.key("saveProjectStaffPlans"));
        var project = require(id);
        if ("N".equals(project.use_yn())) {
            throw ApiException.badRequest("사용 안 함 프로젝트는 조회만 가능합니다.", "use_yn");
        }
        if (input == null) throw ApiException.badRequest("목록을 확인하세요.", "items");
        List<StaffPlan> before = mapper.plans(id);
        Map<String, StaffPlan> stored = new HashMap<>();
        before.forEach(row -> stored.put(key(row.plan_ym(), row.job_cd(), row.grd_cd()), row));
        Set<String> keys = new HashSet<>();
        List<StaffPlanInput> nonzero = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            var row = input.get(i);
            String prefix = "items[" + i + "].";
            YearMonth month = CalendarValues.month(row.plan_ym(), prefix + "plan_ym");
            var mm = ProjectValues.decimal(row.need_mm_qty(), 4, prefix + "need_mm_qty");
            if (mm.signum() < 0) throw ApiException.badRequest("0~99.99 사이로 입력하세요.", prefix + "need_mm_qty");
            String key = key(row.plan_ym(), row.job_cd(), row.grd_cd());
            var old = stored.get(key);
            boolean retained = old != null && old.src_need_id() == null && unchanged(old, row);
            code("JOB", row.job_cd(), prefix + "job_cd", retained);
            code("GRADE", row.grd_cd(), prefix + "grd_cd", retained);
            if (mm.signum() == 0) continue;
            if (!keys.add(key) || (old != null && old.src_need_id() != null)) {
                throw ApiException.badRequest("중복되거나 원천 연결 계획과 겹치는 칸입니다.", "items");
            }
            boolean inPeriod = project.start() != null && project.end() != null
                && !month.isBefore(YearMonth.from(project.start())) && !month.isAfter(YearMonth.from(project.end()));
            if (!inPeriod && !retained) {
                throw ApiException.badRequest("수행 기간 밖에는 새 계획을 입력할 수 없습니다.", prefix + "plan_ym");
            }
            nonzero.add(row);
        }
        mapper.deleteManual(id);
        for (var row : nonzero) {
            mapper.insert(new StaffWrite(id, row, row.need_mm_qty().setScale(0, RoundingMode.CEILING), user.userId()));
        }
        var after = mapper.plans(id);
        mapper.history(new StaffHistory(Long.toString(id), summary(before), summary(after), user.userId()));
        return new StaffPlans(after, mapper.actual(id));
    }
    private String summary(List<StaffPlan> rows) {
        var sum = rows.stream().map(StaffPlan::need_mm_qty).reduce(BigDecimal.ZERO, BigDecimal::add);
        return "인원 계획 " + rows.size() + "건 · 합계 " + sum.setScale(2).toPlainString() + " MM";
    }
}
