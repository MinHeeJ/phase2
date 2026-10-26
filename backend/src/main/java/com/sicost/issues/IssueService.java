package com.sicost.issues;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.ProjectValues;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.issues.IssueModels.*;

@Service
@Transactional(readOnly=true)
public class IssueService {
    private final IssueMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public IssueService(IssueMapper mapper, CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper=mapper; this.user=user; this.permissions=permissions; this.clock=clock;
    }
    private IssueProject lock(long id) {
        var project=mapper.lockProject(IssueInputParser.id(id,"prj_id"));
        if (project==null) throw ApiException.notFound("프로젝트가 없습니다.");
        return project;
    }
    private void writable(IssueProject project) {
        if (!"Y".equals(project.use_yn()) || !Set.of("PLAN","RUN","HOLD").contains(project.prj_stts_cd()))
            throw ApiException.conflict("PROJECT_CLOSED","입력할 수 없는 프로젝트입니다.",null);
    }
    public IssuesData list(Map<String,String> params) {
        ProjectActions.can(permissions,"listIssues",false);
        var query=IssueInputParser.query(params,ProjectValues.today(clock));
        return new IssuesData(mapper.list(query),mapper.count(query),query.page(),query.size(),mapper.openCount());
    }
    @Transactional
    public IssueRow create(IssueCreate request) {
        ProjectActions.can(permissions,"createIssue",false);
        writable(lock(request.prj_id()));
        LocalDate today=ProjectValues.today(clock);
        long id=mapper.insert(new IssueInsert(request.prj_id(),request.input(),today,
            "CLOSED".equals(request.input().issue_stts_cd())?today:null,user.userId()));
        return mapper.row(id);
    }
    @Transactional
    public IssueRow update(long id, IssueInput input) {
        ProjectActions.can(permissions,"updateIssue",false);
        IssueInputParser.id(id,"issueId");
        Long parent=mapper.parent(id);
        if (parent==null) throw ApiException.notFound("이슈가 없습니다.");
        var project=lock(parent);
        var before=mapper.lockIssue(id);
        if (before==null || before.prj_id()!=parent) throw ApiException.notFound("이슈가 없습니다.");
        writable(project);
        var old=before.columns(); var after=input.columns();
        LocalDate end="CLOSED".equals(input.issue_stts_cd())
            ? ("CLOSED".equals(before.issue_stts_cd())?before.end_dt():ProjectValues.today(clock)) : null;
        after.put("end_dt",end);
        var changes=new LinkedHashMap<String,Object>();
        after.forEach((key,value)-> { if (!Objects.equals(value,old.get(key))) changes.put(key,value); });
        if (!changes.isEmpty()) {
            mapper.update(new IssueUpdate(id,changes,user.userId()));
            changes.forEach((key,value)->mapper.history(new IssueHistory(Long.toString(id),key,
                text(old.get(key)),text(value),user.userId())));
        }
        return mapper.row(id);
    }
    private String text(Object value) { return value==null?null:value.toString(); }
}
