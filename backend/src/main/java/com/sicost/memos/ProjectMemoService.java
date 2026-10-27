package com.sicost.memos;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.memos.ProjectMemoModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectMemoService {
    private final ProjectMemoMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public ProjectMemoService(ProjectMemoMapper mapper, CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper = mapper; this.user = user; this.permissions = permissions; this.clock = clock;
    }
    private ProjectState lock(long id) {
        ProjectMemoInput.id(id, "id");
        var project = mapper.lockProject(id);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        return project;
    }
    private void writable(ProjectState project) {
        if (!"Y".equals(project.use_yn()) || !Set.of("PLAN", "RUN", "HOLD").contains(project.prj_stts_cd())) {
            throw ApiException.conflict("PROJECT_CLOSED", "입력할 수 없는 프로젝트입니다. 기록은 읽기만 할 수 있습니다.", null);
        }
    }
    private BigDecimal rate(ProjectState project, MemoInput input) {
        if ("MO".equals(project.cntrct_ty_cd()) || "DP".equals(project.cntrct_ty_cd())) return null;
        var value = input.actl_prgs_rt();
        if (value != null && value.isNumber()) {
            try {
                int number = value.decimalValue().intValueExact();
                if (number >= 0 && number <= 100) return BigDecimal.valueOf(number);
            } catch (ArithmeticException ignored) { }
        }
        throw ApiException.badRequest("실적 진척률은 0~100 사이 정수로 입력해 주세요.", "actl_prgs_rt");
    }
    private MemoRow proposal(ProjectState project, MemoRow before, MemoInput input, java.time.LocalDate today) {
        return new MemoRow(before == null ? 0 : before.memo_id(), project.prj_id(),
            before == null ? today : before.wrt_dt(), rate(project, input),
            input.cmpl_work_desc(), input.plan_work_desc(), input.rqst_desc());
    }
    private WeeklyMemo output(MemoRow row) {
        Integer rate = null;
        if (row.actl_prgs_rt() != null) {
            try { rate = row.actl_prgs_rt().intValueExact(); }
            catch (ArithmeticException ex) { throw ApiException.badRequest("저장된 실적 진척률의 정수 범위를 확인해 주세요.", "actl_prgs_rt"); }
            if (rate < 0 || rate > 100) throw ApiException.badRequest("저장된 실적 진척률의 범위를 확인해 주세요.", "actl_prgs_rt");
        }
        return new WeeklyMemo(row.memo_id(), row.prj_id(), row.wrt_dt(), rate,
            row.cmpl_work_desc(), row.plan_work_desc(), row.rqst_desc());
    }
    @Transactional
    public WeeklyMemo save(long id, MemoInput input) {
        ProjectActions.can(permissions, "saveProjectMemo", false);
        var project = lock(id);
        writable(project);
        var today = ProjectValues.today(clock);
        var before = mapper.today(new MemoDate(id, today));
        var row = proposal(project, before, input, today);
        long memoId;
        if (before == null) memoId = mapper.insert(new MemoWrite(id, row, user.userId()));
        else { memoId = before.memo_id(); mapper.update(new MemoWrite(id, row, user.userId())); }
        return output(mapper.row(new MemoKey(id, memoId)));
    }
    @Transactional
    public WeeklyMemo update(long id, long memoId, MemoInput input) {
        ProjectMemoInput.id(memoId, "memoId");
        ProjectActions.can(permissions, "updateProjectMemo", false);
        var project = lock(id);
        var before = mapper.row(new MemoKey(id, memoId));
        if (before == null) throw ApiException.notFound("메모가 없습니다.");
        writable(project);
        var row = proposal(project, before, input, before.wrt_dt());
        var latest = mapper.latest(id);
        if (latest == null || latest.memo_id() != memoId) {
            throw ApiException.conflict("NOT_LATEST_MEMO", "가장 최근 메모만 수정할 수 있습니다.", null);
        }
        mapper.update(new MemoWrite(id, row, user.userId()));
        return output(mapper.row(new MemoKey(id, memoId)));
    }
}
