package com.sicost.phases;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.ProjectValues;
import java.time.*;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.phases.ProjectPhaseModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectPhaseService {
    private final ProjectPhaseMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;

    public ProjectPhaseService(ProjectPhaseMapper mapper, CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper = mapper; this.user = user; this.permissions = permissions; this.clock = clock;
    }
    private PhaseKey key(long id, long phsId) {
        if (id < 1) throw ApiException.badRequest("프로젝트 번호를 확인하세요.", "id");
        if (phsId < 1) throw ApiException.badRequest("단계 번호를 확인하세요.", "phsId");
        return new PhaseKey(id, phsId, ProjectValues.today(clock));
    }
    private Phase writable(PhaseKey key) {
        var project = mapper.lockProject(key.id());
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        var row = mapper.row(key);
        if (row == null) throw ApiException.notFound("단계가 없습니다.");
        if (!"Y".equals(project.use_yn()) || !("PLAN".equals(project.prj_stts_cd())
                || "RUN".equals(project.prj_stts_cd()) || "HOLD".equals(project.prj_stts_cd()))) {
            throw ApiException.conflict("PROJECT_CLOSED", "입력할 수 없는 프로젝트입니다.", null);
        }
        return row;
    }
    private void audit(PhaseKey key, String column, LocalDate before, LocalDate after) {
        if (!Objects.equals(before, after)) mapper.audit(new PhaseAudit(Long.toString(key.phsId()), column,
            before == null ? null : before.toString(), after == null ? null : after.toString(), user.userId()));
    }
    @Transactional
    public Phase complete(long id, long phsId) {
        ProjectActions.can(permissions, "updateProjectPhaseCompletion", false);
        var key = key(id, phsId);
        var before = writable(key);
        if (before.cmpl_dt() == null && (before.plan_bgn_dt() == null || before.plan_bgn_dt().isAfter(key.today()))) {
            throw ApiException.conflict("PHASE_NOT_STARTED", "아직 시작하지 않은 단계입니다.", null);
        }
        if (mapper.complete(new PhaseWrite(key, null, user.userId())) == 0) {
            throw ApiException.conflict("ALREADY_DONE", "이미 완료된 단계입니다.", null);
        }
        audit(key, "cmpl_dt", before.cmpl_dt(), key.today());
        return mapper.row(key);
    }
    @Transactional
    public Phase uncomplete(long id, long phsId) {
        ProjectActions.can(permissions, "updateProjectPhaseUncompletion", false);
        var key = key(id, phsId);
        var before = writable(key);
        if (before.cmpl_dt() != null) {
            mapper.uncomplete(new PhaseWrite(key, null, user.userId()));
            audit(key, "cmpl_dt", before.cmpl_dt(), null);
            return mapper.row(key);
        }
        return before;
    }
    @Transactional
    public Phase update(long id, long phsId, PeriodInput input) {
        ProjectActions.can(permissions, "updateProjectPhase", false);
        validate(input);
        var key = key(id, phsId);
        var before = writable(key);
        if (before.cmpl_dt() != null) throw ApiException.conflict("PHASE_DONE", "완료된 단계의 기간은 수정할 수 없습니다.", null);
        if (!Objects.equals(before.plan_bgn_dt(), input.plan_bgn_dt()) || !Objects.equals(before.plan_end_dt(), input.plan_end_dt())) {
            mapper.period(new PhaseWrite(key, input, user.userId()));
            audit(key, "plan_bgn_dt", before.plan_bgn_dt(), input.plan_bgn_dt());
            audit(key, "plan_end_dt", before.plan_end_dt(), input.plan_end_dt());
            return mapper.row(key);
        }
        return before;
    }
    static void validate(PeriodInput input) {
        if (input == null || input.plan_bgn_dt() == null) throw ApiException.badRequest("예정 시작일을 입력하세요.", "plan_bgn_dt");
        if (input.plan_end_dt() == null) throw ApiException.badRequest("예정 종료일을 입력하세요.", "plan_end_dt");
        if (input.plan_end_dt().isBefore(input.plan_bgn_dt())) {
            throw ApiException.badRequest("예정 종료일은 시작일과 같거나 뒤여야 합니다.", "plan_end_dt");
        }
    }
}
