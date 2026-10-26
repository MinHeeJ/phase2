package com.sicost.projectsops;

import com.sicost.apiops.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.projectsops.ProjectProgressModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProjectProgressService {
    private final ProjectProgressMapper mapper;
    private final ProjectBoardMapper board;
    private final ProgressParameterService parameters;
    private final ProjectCostService costs;
    private final PermissionService permissions;
    private final Clock clock;
    public ProjectProgressService(ProjectProgressMapper mapper, ProjectBoardMapper board,
            ProgressParameterService parameters, ProjectCostService costs, PermissionService permissions, Clock clock) {
        this.mapper=mapper; this.board=board; this.parameters=parameters;
        this.costs=costs; this.permissions=permissions; this.clock=clock;
    }
    private ProjectState require(long id) {
        if (id < 1) throw ApiException.badRequest("프로젝트 번호를 확인하세요.", "id");
        var state=mapper.state(id);
        if (state==null) throw ApiException.notFound("프로젝트가 없습니다.");
        return state;
    }
    private WeeklyMemo memo(MemoSource row, boolean manual) {
        Integer rate=null;
        if (manual && row.actl_prgs_rt()!=null) {
            try {
                rate=row.actl_prgs_rt().intValueExact();
                if (rate<0 || rate>100) throw new ArithmeticException();
            } catch (ArithmeticException ex) {
                throw ApiException.badRequest("기존 주간 메모 실적 진척률이 0~100 정수가 아닙니다. 데이터 정합성을 확인하세요.", "actl_prgs_rt");
            }
        }
        return new WeeklyMemo(row.memo_id(),row.prj_id(),row.wrt_dt(),rate,
            Objects.toString(row.cmpl_work_desc(),""),Objects.toString(row.plan_work_desc(),""),row.rqst_desc());
    }
    public Progress get(long id) {
        permissions.can(ProjectActions.key("getProjectProgress"));
        var state=require(id);
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String month=YearMonth.from(today).toString();
        var params=parameters.get(month);
        boolean manual=Set.of("TK","IN").contains(state.cntrct_ty_cd());
        var memos=mapper.memos(id).stream().map(m->memo(m,manual)).toList();
        var phases=board.phases(id).stream().map(p->ProjectProgressCalculator.phase(p,today)).toList();
        var cost="TK".equals(state.cntrct_ty_cd()) ? costs.calculate(id,month) : null;
        var row=ProjectProgressCalculator.calculate(mapper.source(new ProgressRead(id,today)),phases,cost,params,today);
        boolean writable="Y".equals(state.use_yn()) && Set.of("PLAN","RUN","HOLD").contains(state.prj_stts_cd());
        return new Progress(id,row.pmName(),row.currentPhase(),row.plan_prgs_rt(),row.actl_prgs_rt(),row.schedule_diff_rt(),
            row.cost_use_rt(),row.cost_progress_diff_rt(),row.cumulative_cost_amt(),row.budget_amt(),
            writable ? row.light() : null,writable ? row.reasons() : List.of(),row.memo_elapsed_days(),row.last_memo_dt(),
            phases,memos,cost==null ? Collections.emptyList() : cost.missing_salary_months());
    }
    public ProjectIssues issues(long id) {
        permissions.can(ProjectActions.key("listProjectIssues"));
        require(id);
        var items=mapper.issues(id);
        int count=Math.toIntExact(items.stream().filter(i->Set.of("OPEN","DOING").contains(i.issue_stts_cd())).count());
        return new ProjectIssues(items,count);
    }
}
