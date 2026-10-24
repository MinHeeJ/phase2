package com.sicost.workstatus;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.workstatus.WorkStatusModels.*;

@Service
@Transactional(readOnly=true)
public class WorkStatusService {
    private final WorkStatusMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    public WorkStatusService(WorkStatusMapper mapper,CurrentUser user,PermissionService permissions) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;
    }
    private void developer(long id) {
        WorkStatusInputParser.id(id,"id");
        if(!mapper.developerExists(id))throw ApiException.notFound("개발자가 없습니다.");
    }
    private WorkStatusRow require(WorkStatusKey key) {
        WorkStatusInputParser.id(key.histId(),"histId");developer(key.devId());
        WorkStatusRow row=mapper.detail(key);
        if(row==null)throw ApiException.notFound("근무 상태가 없습니다.");return row;
    }
    public PageResult<WorkStatusRow> list(long id,String status,DeveloperPaging paging) {
        permissions.can("UI-02.read");developer(id);
        if(status!=null)WorkStatusInputParser.code(status,"status");
        String sort=paging.sort()==null || paging.sort().isBlank()?"bgn_dt,desc":paging.sort();
        String[] parts=sort.split(",",-1);
        if(parts.length!=2 || !Set.of("work_stts_cd","bgn_dt","end_dt","paid_yn","rmrk_desc","work_stts_hist_id").contains(parts[0]) || !Set.of("asc","desc").contains(parts[1]))throw ApiException.badRequest("정렬을 확인하세요.","sort");
        String order=parts[0]+("asc".equals(parts[1])?" ASC":" DESC")+", work_stts_hist_id DESC";
        WorkStatusQuery query=new WorkStatusQuery(id,status,paging.size(),paging.toPageRequest().offset(),order);
        return paging.result(mapper.list(query),mapper.count(query));
    }
    private void rules(WorkStatusWrite write) {
        if("RESIGN".equals(write.input().work_stts_cd())) {
            if(mapper.resignationExists(write))throw ApiException.badRequest("이미 퇴사가 등록되어 있습니다.","work_stts_cd");
        } else {
            WorkStatusRow overlap=mapper.overlap(write);
            if(overlap!=null) {
                String label=mapper.codeName(overlap.work_stts_cd());
                throw ApiException.conflict("PERIOD_OVERLAP","다른 근무 상태("+(label==null?overlap.work_stts_cd():label)+" "+overlap.bgn_dt()+"~"+(overlap.end_dt()==null?"–":overlap.end_dt())+")와 기간이 겹칩니다.","bgn_dt");
            }
        }
    }
    private static WorkStatusInput input(WorkStatusRow row) {
        return new WorkStatusInput(row.work_stts_cd(),row.bgn_dt(),row.end_dt(),row.paid_yn(),row.rmrk_desc());
    }
    private void open(WorkStatusInput input) {
        if(mapper.closed(input))throw ApiException.conflict("CLOSED_MONTH","마감한 달은 수정·삭제할 수 없습니다.",null);
    }
    private void synchronize(WorkStatusRow before,WorkStatusRow after) {
        if(after!=null && "RESIGN".equals(after.work_stts_cd()))mapper.resignation(new WorkStatusResignation(after.dev_id(),after.bgn_dt(),user.userId()));
        else if(before!=null && "RESIGN".equals(before.work_stts_cd()))mapper.resignation(new WorkStatusResignation(before.dev_id(),null,user.userId()));
    }
    @Transactional
    public WorkStatusRow create(long id,WorkStatusInput requested) {
        permissions.can("UI-02.update");developer(id);
        WorkStatusInput normalized=WorkStatusInputParser.normalize(requested);
        rules(new WorkStatusWrite(id,0,normalized,user.userId()));
        long histId=mapper.nextId();
        WorkStatusRow row=mapper.insert(new WorkStatusWrite(id,histId,normalized,user.userId()));
        synchronize(null,row);
        mapper.audit(new WorkStatusAudit(histId,"INSERT",null,mapper.snapshot(new WorkStatusKey(id,histId)),user.userId()));
        return row;
    }
    @Transactional
    public WorkStatusRow update(long id,long histId,WorkStatusInput requested) {
        permissions.can("UI-02.update");WorkStatusKey key=new WorkStatusKey(id,histId);
        WorkStatusRow before=require(key);
        WorkStatusInput normalized=WorkStatusInputParser.normalize(requested);
        open(input(before));open(normalized);
        WorkStatusWrite write=new WorkStatusWrite(id,histId,normalized,user.userId());rules(write);
        String snapshot=mapper.snapshot(key);
        WorkStatusRow row=mapper.update(write);
        if(row==null)throw ApiException.notFound("근무 상태가 없습니다.");
        synchronize(before,row);
        mapper.audit(new WorkStatusAudit(histId,"UPDATE",snapshot,mapper.snapshot(key),user.userId()));return row;
    }
    @Transactional
    public WorkStatusRemoval delete(long id,long histId) {
        permissions.can("UI-02.update");WorkStatusKey key=new WorkStatusKey(id,histId);
        WorkStatusRow before=require(key);open(input(before));String snapshot=mapper.snapshot(key);
        if(mapper.delete(key)!=1)throw ApiException.notFound("근무 상태가 없습니다.");
        synchronize(before,null);
        mapper.audit(new WorkStatusAudit(histId,"DELETE",snapshot,null,user.userId()));
        return new WorkStatusRemoval(histId,true,mapper.resignationDate(id));
    }
}
