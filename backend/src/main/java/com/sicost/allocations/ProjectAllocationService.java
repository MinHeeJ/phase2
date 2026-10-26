package com.sicost.allocations;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.ProjectValues.PerformancePeriod;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.allocations.ProjectAllocationModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectAllocationService {
    private final ProjectAllocationMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;

    public ProjectAllocationService(ProjectAllocationMapper mapper, CurrentUser user,
            PermissionService permissions) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
    }
    private AllocationProject require(long id) {
        if (id < 1) throw ApiException.badRequest("프로젝트 번호를 확인하세요.", "id");
        var project = mapper.project(id);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        if (!"TK".equals(project.cntrct_ty_cd())) {
            throw ApiException.badRequest("턴키 프로젝트만 배분표를 사용합니다.", "cntrct_ty_cd");
        }
        return project;
    }
    private List<AllocationLine> readLines(AllocationScope scope) {
        return mapper.lines(scope).stream()
                .map(l -> l.withMonths(mapper.months(l.aloc_line_id()))).toList();
    }
    public Allocations get(long id, String stage) {
        ProjectActions.can(permissions, "getProjectAllocations", false);
        stage = ProjectAllocationInput.stage(stage, false);
        var project = require(id);
        String active = mapper.activeStage(id);
        String selected = stage == null ? (active == null ? "EST" : active) : stage;
        return new Allocations(selected, active, readLines(new AllocationScope(id, selected)),
                project.cur_cntrct_amt(), mapper.reallocation(id));
    }
    @Transactional
    public Allocations save(long id, String stage, List<AllocationLine> lines) {
        ProjectActions.can(permissions, "saveProjectAllocations", false);
        stage = ProjectAllocationInput.stage(stage, true);
        var project = require(id);
        if (!"Y".equals(project.use_yn())) {
            throw ApiException.badRequest("사용 안 함 프로젝트는 수정할 수 없습니다.", "use_yn");
        }
        var scope = new AllocationScope(id, stage);
        var old = readLines(scope);
        validate(project, lines, old);
        mapper.deleteMonths(scope);
        mapper.deleteLines(scope);
        for (var line : lines) {
            long lineId = mapper.insertLine(new AllocationWrite(scope, line, user.userId()));
            for (var month : line.months()) {
                if (month.aloc_amt() != null || month.mm_qty() != null) {
                    mapper.insertMonth(new AllocationMonthWrite(lineId, month));
                }
            }
        }
        mapper.clearReallocation(id);
        mapper.audit(new AllocationAudit(Long.toString(id), stage,
                summary(stage, old), summary(stage, lines), user.userId()));
        return get(id, stage);
    }
    private void validate(AllocationProject project, List<AllocationLine> lines,
            List<AllocationLine> old) {
        PerformancePeriod period = new PerformancePeriod(project.begin_date(), project.end_date());
        var remaining = new ArrayList<>(old);
        for (var line : lines) {
            AllocationLine before = remaining.stream().filter(o -> sameIdentity(o, line))
                    .findFirst().orElse(null);
            if (before != null) remaining.remove(before);
            if (line.dev_id() != null && (before == null || !Objects.equals(before.dev_id(), line.dev_id()))
                    && !mapper.activeDeveloper(line.dev_id())) {
                throw ApiException.badRequest("사용 중인 개발자를 골라 주세요.", "dev_id");
            }
            code("JOB", line.job_cd(), before == null ? null : before.job_cd(), "job_cd");
            code("ASSIGN_KIND", line.asgn_se_cd(),
                    before == null ? null : before.asgn_se_cd(), "asgn_se_cd");
            for (var month : line.months()) {
                if (period.contains(YearMonth.parse(month.aloc_ym()))) continue;
                boolean unchanged = before != null && before.months().stream().anyMatch(m ->
                        m.aloc_ym().equals(month.aloc_ym())
                        && (month.aloc_amt() == null || Objects.equals(m.aloc_amt(), month.aloc_amt()))
                        && (month.mm_qty() == null || equalDecimal(m.mm_qty(), month.mm_qty())));
                if (!unchanged) {
                    throw ApiException.badRequest("수행 기간 밖의 새 월 값은 입력할 수 없습니다.", "aloc_ym");
                }
            }
        }
    }
    private void code(String group, String value, String old, String field) {
        if (value != null && !Objects.equals(value, old)
                && !mapper.activeCode(new AllocationCode(group, value))) {
            throw ApiException.badRequest("사용 중인 코드를 골라 주세요.", field);
        }
    }
    private static boolean equalDecimal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
    private static boolean sameIdentity(AllocationLine a, AllocationLine b) {
        return a.line_ty_cd().equals(b.line_ty_cd()) && Objects.equals(a.dev_id(), b.dev_id())
                && Objects.equals(a.item_nm(), b.item_nm()) && Objects.equals(a.job_cd(), b.job_cd())
                && Objects.equals(a.asgn_bgn_dt(), b.asgn_bgn_dt());
    }
    private static String summary(String stage, List<AllocationLine> lines) {
        BigDecimal sum = lines.stream().flatMap(l -> l.months().stream())
                .map(m -> m.aloc_amt() == null ? BigDecimal.ZERO : BigDecimal.valueOf(m.aloc_amt()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return stage + " 배분표 " + lines.size() + "건 · 합계 " + sum.toPlainString() + "원";
    }
}
