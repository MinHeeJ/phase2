package com.sicost.expenses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.expenses.ProjectExpenseModels.*;

@Service
@Transactional(readOnly=true)
public class ProjectExpenseService {
    private final ProjectExpenseMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;
    public ProjectExpenseService(ProjectExpenseMapper mapper, CurrentUser user,
            PermissionService permissions, ObjectMapper json) {
        this.mapper=mapper;
        this.user=user;
        this.permissions=permissions;
        this.json=json;
    }
    private void project(long id, boolean write) {
        ProjectExpenseInput.id(id, "id");
        String use=mapper.projectUse(id);
        if (use==null) throw ApiException.notFound("프로젝트가 없습니다.");
        if (write && !"Y".equals(use))
            throw ApiException.badRequest("사용 안 함으로 바꾼 프로젝트입니다.", "use_yn");
    }
    private ProjectExpense require(ExpenseKey key) {
        ProjectExpenseInput.id(key.expenseId(), "expnsId");
        var row=mapper.row(key);
        if (row==null) throw ApiException.notFound("프로젝트 직접 경비가 없습니다.");
        return row;
    }
    private void open(String month) {
        if ("CLOSED".equals(mapper.monthStatus(month)))
            throw ApiException.conflict("CLOSED_MONTH", month+"은 마감된 달이라 입력할 수 없습니다.", "expns_ym");
    }
    private void choices(ExpenseInput input, ProjectExpense before) {
        if ((before==null || !Objects.equals(before.expns_item_cd(),input.expns_item_cd()))
                && !mapper.activeCode(input.expns_item_cd()))
            throw ApiException.badRequest("사용 중인 경비 구분을 골라 주세요.", "expns_item_cd");
        open(input.expns_ym());
    }
    public List<ProjectExpense> list(long id) {
        permissions.can(ProjectActions.key("listProjectExpenses"));
        project(id,false);
        return mapper.list(id);
    }
    @Transactional
    public ProjectExpense create(long id, ExpenseInput input) {
        permissions.can(ProjectActions.key("createProjectExpense"));
        project(id,true);
        choices(input,null);
        long expenseId=mapper.insert(new ExpenseWrite(id,0,input,user.userId()));
        var after=require(new ExpenseKey(id,expenseId));
        audit(id,"INSERT",null,after);
        return after;
    }
    @Transactional
    public ProjectExpense update(long id, long expenseId, ExpenseInput input) {
        permissions.can(ProjectActions.key("updateProjectExpense"));
        project(id,true);
        var key=new ExpenseKey(id,expenseId);
        var before=require(key);
        open(before.expns_ym());
        choices(input,before);
        if (mapper.update(new ExpenseWrite(id,expenseId,input,user.userId()))!=1)
            throw ApiException.notFound("프로젝트 직접 경비가 없습니다.");
        var after=require(key);
        audit(id,"UPDATE",before,after);
        return after;
    }
    @Transactional
    public ExpenseRemoval delete(long id, long expenseId) {
        permissions.can(ProjectActions.key("updateProjectExpenseRemoval"));
        project(id,true);
        var key=new ExpenseKey(id,expenseId);
        var before=require(key);
        open(before.expns_ym());
        if (mapper.delete(key)!=1) throw ApiException.notFound("프로젝트 직접 경비가 없습니다.");
        audit(id,"DELETE",before,null);
        return new ExpenseRemoval(id,expenseId);
    }
    private String snapshot(ProjectExpense row) {
        if (row==null) return null;
        try { return json.writeValueAsString(row); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private void audit(long id, String type, ProjectExpense before, ProjectExpense after) {
        mapper.audit(new ExpenseAudit(Long.toString(id),type,snapshot(before),snapshot(after),user.userId()));
    }
}
