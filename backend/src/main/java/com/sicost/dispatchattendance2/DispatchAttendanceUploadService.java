package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.Audit;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.PersonnelUploadModels.Result;
import com.sicost.uploadsops.UploadModels.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Service
public class DispatchAttendanceUploadService implements UploadFormDelegate {
    private final DispatchAttendanceUploadMapper mapper;
    private final DispatchAttendanceUploadForm form;
    private final PersonnelUploadMapper uploads;
    private final UploadMapper legacy;
    private final UploadInspectionContexts inspections;
    private final UploadWorkbookWriter writer;
    private final BillingMutationService mutations;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Map<Long,Review> reviews=new HashMap<>();
    private static final class Review {
        final long id;final Scope scope;final String name;final Path path;final Parsed parsed;final Instant expires;
        boolean superseded;Applied completed;
        Review(long id,Scope scope,String name,Path path,Parsed parsed,Instant expires){this.id=id;this.scope=scope;this.name=name;this.path=path;this.parsed=parsed;this.expires=expires;}
    }
    public DispatchAttendanceUploadService(DispatchAttendanceUploadMapper mapper,DispatchAttendanceUploadForm form,
        PersonnelUploadMapper uploads,UploadMapper legacy,UploadInspectionContexts inspections,UploadWorkbookWriter writer,
        BillingMutationService mutations,CurrentUser user,PermissionService permissions,Clock clock,ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper;this.form=form;this.uploads=uploads;this.legacy=legacy;this.inspections=inspections;this.writer=writer;
        this.mutations=mutations;this.user=user;this.permissions=permissions;this.clock=clock;this.json=json;this.transaction=new TransactionTemplate(manager);
    }
    public boolean supports(String type){return "B.3".equals(type);}
    public boolean owns(long id){synchronized(reviews){return reviews.containsKey(id);}}
    private void guard(Scope scope) {
        BillingRules.requireOpen(BillingRules.month(scope.month(),"month"),uploads.monthStatus(scope.month()),"month");
        if(mapper.billed(scope))throw ApiException.conflict("ATTENDANCE_BILLED","확정 청구에 사용된 근태는 교체할 수 없습니다.","month");
    }
    private Parsed parse(Path path,Scope scope) {
        var project=mapper.project(scope.projectId());
        if(project==null || !"DP".equals(project.type()) || !"Y".equals(project.enabled()) || project.basis()==null || !Set.of("DAY","HOUR").contains(project.basis()))throw BillingRules.invalid("projectId");
        try(InputStream in=Files.newInputStream(path)){return form.parse(in,scope,project,mapper.assignments(scope));}
        catch(IOException e){throw BillingRules.invalid("file");}
    }
    public Result validate(String month,long projectId,String name,Path path) {
        permissions.can("UI-13.upload");var ym=BillingRules.month(month,"month");if(projectId<1)throw BillingRules.invalid("projectId");
        var scope=new Scope(projectId,month,ym.atDay(1),ym.atEndOfMonth());guard(scope);
        Parsed parsed=parse(path,scope);long id=uploads.nextUploadId();Instant now=clock.instant();
        Review review=new Review(id,scope,name,path,parsed,now.plusSeconds(1800));Result result=result(review);
        if(!result.can_apply())transaction.executeWithoutResult(status->{mapper.review(write(review,result));saveIssues(review);});
        inspections.publish(new UploadInspectionContexts.Inspection(id,"B.3",month,result.row_count(),result.error_count(),result.warning_count(),result.can_apply(),true),now);
        synchronized(reviews){reviews.entrySet().removeIf(e->!now.isBefore(e.getValue().expires));reviews.put(id,review);}return result;
    }
    private Review require(long id){Review r;synchronized(reviews){r=reviews.get(id);}if(r==null||!clock.instant().isBefore(r.expires))throw new ApiException(410,"VALIDATION_EXPIRED","검사 후 30분이 지났습니다. 다시 올려 주세요.","uploadId");return r;}
    private Result result(Review r){int errors=(int)r.parsed.rows().stream().filter(p->p.severity().equals("ERROR")).count();int warnings=(int)r.parsed.rows().stream().filter(p->p.severity().equals("WARNING")).count();return new Result(r.id,"B.3",r.scope.month(),r.parsed.rows().size(),errors,warnings,errors==0&&!r.superseded&&r.completed==null,0,r.scope.month()+" 선택 프로젝트의 근태 전체를 새 파일로 바꿉니다. 연결된 작성 청구는 다시 계산 전까지 유지됩니다.","/api/uploads/"+r.id+"/issues",true);}
    private ReviewWrite write(Review r,Result result){return new ReviewWrite(r.id,r.scope,r.name,r.path.toString(),result,user.userId());}
    public Result revalidate(long id){Review r=require(id);synchronized(r){if(r.completed!=null)throw ApiException.conflict("ALREADY_APPLIED","이미 반영한 파일입니다.","uploadId");Result result=validate(r.scope.month(),r.scope.projectId(),r.name,r.path);r.superseded=true;inspections.invalidate(id);return result;}}
    public Applied apply(long id) {
        permissions.can("UI-13.upload");Review r=require(id);synchronized(r){
            if(r.completed!=null)return r.completed;
            if(r.superseded)throw ApiException.conflict("STALE_VALIDATION","다시 검사해 주세요.","upload_id");
            if(!result(r).can_apply())throw BillingRules.invalid("upload_id");
            Applied applied=transaction.execute(status->{
                mutations.lockProject(r.scope.projectId());guard(r.scope);
                Parsed current=parse(r.path,r.scope);
                if(!current.equals(r.parsed))throw ApiException.conflict("STALE_VALIDATION","파일 또는 기준정보가 변경되었습니다. 다시 검사하세요.","upload_id");
                List<Attendance> before=mapper.existing(r.scope);mapper.review(write(r,result(r)));
                mapper.delete(r.scope);
                for(Attendance row:before)audit(row.atnd_id(),"DELETE",row,null,r.id);
                for(Preview preview:r.parsed.rows()) {Attendance row=preview.attendance();Long key=mapper.insert(new AttendanceWrite(r.scope,row,r.id,user.userId()));if(key==null)throw new IllegalStateException("근태 저장 결과가 없습니다.");audit(key,"INSERT",null,row,r.id);}
                saveIssues(r);legacy.access(new UploadAccess("UPLOAD",r.name.substring(0,Math.min(200,r.name.length())),json(Map.of("upldId",r.id,"appliedRows",r.parsed.rows().size())),user.userId()));
                return new Applied(r.id,"B.3",r.parsed.rows().size(),"선택 범위의 파견 근태를 반영했습니다.");
            });r.completed=Objects.requireNonNull(applied);inspections.invalidate(id);return applied;
        }
    }
    private void audit(Long id,String type,Attendance before,Attendance after,long upload){mutations.audit(new Audit("bill.dspch_atnd",id.toString(),type,null,json(before),json(after),"B.3 업로드 #"+upload,null));}
    private String json(Object value){if(value==null)return null;try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private void saveIssues(Review r){for(Issue i:r.parsed.issues()){String raw=i.raw_value();legacy.insertIssue(new IssueWrite(r.id,new UploadIssue(i.severity().equals("WARNING")?"WARN":"ERROR",i.row(),i.column(),raw,i.message(),null),raw==null?null:raw.substring(0,Math.min(500,raw.length()))));}}
    public PageResult<Issue> issues(long id,String severity,String column,DeveloperPaging paging){if(severity!=null&&!Set.of("ERROR","WARNING").contains(severity))throw BillingRules.invalid("severity");if(column!=null&&!DispatchAttendanceUploadForm.HEADERS.contains(column)&&!column.equals("파일"))throw BillingRules.invalid("column");var values=require(id).parsed.issues().stream().filter(i->severity==null||severity.equals(i.severity())).filter(i->column==null||column.equals(i.column())).sorted(order(paging.sort(),Issue::row)).toList();return page(values,paging);}
    public PageResult<Preview> rows(long id,String severity,DeveloperPaging paging){if(severity!=null&&!Set.of("NORMAL","WARNING","ERROR").contains(severity))throw BillingRules.invalid("severity");var values=require(id).parsed.rows().stream().filter(i->severity==null||severity.equals(i.severity())).sorted(order(paging.sort(),Preview::row)).toList();return page(values,paging);}
    private static <T> Comparator<T> order(String sort,java.util.function.ToIntFunction<T> row){Comparator<T> order=Comparator.comparingInt(row);if("row,desc".equals(sort))return order.reversed();if(sort!=null&&!sort.equals("row,asc"))throw BillingRules.invalid("sort");return order;}
    private static <T> PageResult<T> page(List<T> all,DeveloperPaging p){int from=(int)Math.min((long)p.page()*p.size(),all.size());return p.result(all.subList(from,Math.min(from+p.size(),all.size())),all.size());}
    public UploadFile errors(long id){Review r=require(id);try(InputStream in=Files.newInputStream(r.path)){var issues=r.parsed.issues().stream().map(i->new UploadIssue(i.severity().equals("WARNING")?"WARN":"ERROR",i.row(),i.column(),i.raw_value(),i.message(),null)).toList();return new UploadFile("파견근태_오류.xlsx",writer.errorFile(in,issues,"B.3"));}catch(IOException e){throw BillingRules.invalid("file");}}
    public UploadFile template(){try{return new UploadFile("파견근태_업로드양식.xlsx",writer.template("B.3",uploads.codes()));}catch(IOException e){throw new IllegalStateException(e);}}
}
