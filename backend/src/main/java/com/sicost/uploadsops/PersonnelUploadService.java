package com.sicost.uploadsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sicost.uploadsops.PersonnelUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

@Service
public class PersonnelUploadService {
    private final PersonnelUploadMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private UploadMapper legacy;
    private final PersonnelUploadForm form;
    private final UploadWorkbookWriter writer;
    private final CurrentUser user;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private UploadInspectionContexts inspections;
    private final TransactionTemplate transaction;
    private final Map<Long,Review> reviews=new HashMap<>();
    private static final class Review {
        final long id;final String type,month,name;final Path path;final Parsed parsed;final Instant expires;final long existing;boolean superseded;Application completed;
        Review(long id,String type,String month,String name,Path path,Parsed parsed,Instant expires,long existing){this.id=id;this.type=type;this.month=month;this.name=name;this.path=path;this.parsed=parsed;this.expires=expires;this.existing=existing;}
    }
    public PersonnelUploadService(PersonnelUploadMapper mapper,PersonnelUploadForm form,UploadWorkbookWriter writer,CurrentUser user,Clock clock,PlatformTransactionManager manager){this.mapper=mapper;this.form=form;this.writer=writer;this.user=user;this.clock=clock;this.transaction=new TransactionTemplate(manager);}
    static void formType(String type){if(!Set.of("B.8","B.1").contains(type))throw ApiException.badRequest("업로드 양식을 확인하세요.","form_type");}
    private void open(String month){CalendarValues.month(month,"month");if("CLOSED".equals(mapper.monthStatus(month)))throw ApiException.conflict("CLOSED_MONTH",month+"은 마감된 달이라 올릴 수 없습니다.","month");}
    private Parsed parse(Path path,String type,String month){if(type.equals("B.1"))open(month);try(InputStream in=Files.newInputStream(path)){return form.parse(in,type,month);}catch(IOException e){throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","원본 파일을 읽을 수 없습니다. 다시 올려 주세요.");}}
    public Result validate(String type,String month,String name,Path path) {
        formType(type);Parsed parsed=parse(path,type,month);long id=mapper.nextUploadId();
        Instant validatedAt=clock.instant();
        Review review=new Review(id,type,month,name,path,parsed,validatedAt.plusSeconds(1800),type.equals("B.1")?mapper.salaryCount(month):0);
        Result result=result(review);
        inspections.publish(new UploadInspectionContexts.Inspection(id,type,month,result.row_count(),
            result.error_count(),result.warning_count(),result.can_apply(),result.normalized_rows_available()),validatedAt);
        synchronized(reviews){reviews.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue().expires));reviews.put(id,review);}
        return result;
    }
    private Review review(long id){if(id<1)throw ApiException.badRequest("업로드 번호를 확인하세요.","uploadId");Review r;synchronized(reviews){r=reviews.get(id);}if(r==null||!clock.instant().isBefore(r.expires))throw new ApiException(410,"VALIDATION_EXPIRED","검사 후 30분이 지났습니다. 다시 올려 주세요.","uploadId");return r;}
    private Result result(Review r){int errors=(int)r.parsed.rows().stream().filter(v->v.severity().equals("ERROR")).count(),warnings=(int)r.parsed.rows().stream().filter(v->v.severity().equals("WARNING")).count();return new Result(r.id,r.type,r.month,r.parsed.rows().size(),errors,warnings,errors==0&&!r.superseded&&r.completed==null,r.existing,r.existing==0?null:r.month+" 급여 "+r.existing+"명분이 이미 있습니다. 반영하면 새 파일로 모두 바뀝니다.","/api/uploads/"+r.id+"/issues",true);}
    public Result result(long id){return result(review(id));}
    public Result revalidate(long id){Review r=review(id);synchronized(r){if(r.completed!=null)throw ApiException.conflict("ALREADY_APPLIED","이미 반영한 파일입니다. 새 파일을 올려 주세요.","uploadId");Result next=validate(r.type,r.month,r.name,r.path);r.superseded=true;inspections.invalidate(id);return next;}}
    public PageResult<Issue> issues(long id,String severity,String column,DeveloperPaging paging){Review r=review(id);if(severity!=null&&!Set.of("ERROR","WARNING").contains(severity))throw ApiException.badRequest("심각도를 확인하세요.","severity");if(column!=null&&(!PersonnelUploadForm.headers(r.type).contains(column)&&!column.equals("퇴사")&&!column.equals("파일")))throw ApiException.badRequest("열을 확인하세요.","column");var list=r.parsed.issues().stream().filter(i->severity==null||i.severity().equals(severity)).filter(i->column==null||i.column().equals(column)).sorted(issueOrder(paging.sort())).toList();return paging.result(page(list,paging),list.size());}
    public PageResult<Preview> rows(long id,String severity,DeveloperPaging paging){Review r=review(id);if(severity!=null&&!Set.of("NORMAL","WARNING","ERROR").contains(severity))throw ApiException.badRequest("심각도를 확인하세요.","severity");Comparator<Preview> order=Comparator.comparingInt(Preview::row);if(desc(paging.sort()))order=order.reversed();var list=r.parsed.rows().stream().filter(i->severity==null||i.severity().equals(severity)).sorted(order).toList();return paging.result(page(list,paging),list.size());}
    private static boolean desc(String sort){if(sort==null||sort.equals("row,asc"))return false;if(sort.equals("row,desc"))return true;throw ApiException.badRequest("정렬을 확인하세요.","sort");}
    private static Comparator<Issue> issueOrder(String sort){Comparator<Issue> order=Comparator.comparingInt(Issue::row);return desc(sort)?order.reversed():order;}
    private static <T> List<T> page(List<T> list,DeveloperPaging p){long offset=(long)p.page()*p.size();return offset>=list.size()?List.copyOf(list.subList(list.size(),list.size())):List.copyOf(list.subList((int)offset,(int)Math.min(list.size(),offset+p.size())));}
    public Application apply(long id){Review r=review(id);synchronized(r){if(r.completed!=null)return r.completed;if(r.type.equals("B.1"))open(r.month);if(r.superseded)throw ApiException.conflict("STALE_VALIDATION","다시 검사해 주세요.","uploadId");if(!result(r).can_apply())throw ApiException.badRequest("오류가 있어 파일 전체를 반영하지 않습니다.","uploadId");try{Application applied=transaction.execute(status->{Parsed current=parse(r.path,r.type,r.month);if(!current.equals(r.parsed))throw ApiException.conflict("STALE_VALIDATION","기준정보가 변경되었습니다. 다시 검사 후 반영해 주세요.","uploadId");return commit(r);});r.completed=Objects.requireNonNull(applied);inspections.invalidate(id);return applied;}catch(DuplicateKeyException e){throw DeveloperManagementNumbers.duplicate();}}}
    private Application commit(Review r){long existing=r.type.equals("B.1")?mapper.salaryCount(r.month):0;String overwrite=existing>0?"Y":"N";mapper.insertReview(new ReviewWrite(r.id,r.type,r.month,r.name,r.path.toString(),result(r),overwrite,user.userId()));List<Long> ids=new ArrayList<>();List<Map<String,Object>> costs=new ArrayList<>();
        if(r.type.equals("B.1")){mapper.deleteSalaries(r.month);mapper.deleteExpenses(r.month);}
        for(Preview row:r.parsed.rows()){
            if(r.type.equals("B.8")){Map<String,Object> input=row.developer();long id=mapper.nextDeveloperId();String number=(String)input.get("mng_no");DeveloperWrite write=new DeveloperWrite(id,number==null?DeveloperManagementNumbers.allocated(id):number,input,r.id,user.userId());mapper.insertDeveloper(write);ids.add(id);
                for(Object c:(List<?>)input.get("sub_job"))mapper.insertJob(new ChildWrite(id,c.toString()));for(Object c:(List<?>)input.get("skill"))mapper.insertSkill(new ChildWrite(id,c.toString()));
                if(input.get("work_status") instanceof Map<?,?> status){long history=mapper.insertStatus(write);Map<String,Object> snapshot=new LinkedHashMap<>();status.forEach((k,v)->snapshot.put(k.toString(),v));snapshot.put("dev_id",id);snapshot.put("work_stts_hist_id",history);mapper.history(new HistoryWrite("mst.dev_work_stts_hist",Long.toString(history),"INSERT","엑셀 업로드 #"+r.id,json(snapshot),user.userId()));}
                mapper.history(new HistoryWrite("mst.dev",Long.toString(id),"INSERT","엑셀 업로드 #"+r.id,json(input),user.userId()));
            }else{Map<String,Object> input=row.salary();long developer=((Number)input.get("dev_id")).longValue();Map<String,Object> saved=mapper.insertSalary(new SalaryWrite(developer,r.month,input,r.id,user.userId()));List<Issue> compared=new ArrayList<>();String diff=PersonnelUploadForm.compare(input,saved,row.row(),compared);if(!Objects.equals(diff,input.get("excel_diff_desc"))){Map<String,Object> update=new HashMap<>();update.put("id",saved.get("mth_cst_id"));update.put("description",diff);mapper.difference(update);}Map<String,Object> cost=new LinkedHashMap<>(saved);cost.remove("mth_cst_id");costs.add(cost);
                for(String[] expense:new String[][]{{"team_expense_amt","TEAM"},{"project_expense_amt","PEXP"}}){long amount=((Number)input.get(expense[0])).longValue();if(!"OS".equals(input.get("emp_se_cd"))&&amount>0)mapper.insertExpense(new ExpenseWrite(developer,r.month,expense[1],amount,r.id,user.userId()));}
            }
        }
        if(r.type.equals("B.1"))mapper.history(new HistoryWrite("cst.mth_cst",r.month,existing>0?"UPDATE":"INSERT","급여 업로드 #"+r.id+" · "+r.parsed.rows().size()+"명",json(costs),user.userId()));
        for(Issue issue:r.parsed.issues()){UploadIssue legacyIssue=new UploadIssue(issue.severity().equals("WARNING")?"WARN":"ERROR",issue.row(),issue.column(),issue.raw_value(),issue.message(),null);String raw=issue.raw_value();legacy.insertIssue(new IssueWrite(r.id,legacyIssue,raw==null?null:raw.substring(0,Math.min(500,raw.length()))));}
        mapper.access(new UploadAccess("UPLOAD",r.name.substring(0,Math.min(200,r.name.length())),json(Map.of("upldId",r.id,"appliedRows",r.parsed.rows().size())),user.userId()));String message=r.type.equals("B.1")?"급여 "+r.parsed.rows().size()+"명분을 반영했습니다.":"개발자 "+ids.size()+"명을 반영했습니다.";
        return new Application(r.id,r.type,r.month,r.parsed.rows().size(),overwrite,List.copyOf(ids),List.copyOf(costs),message);
    }
    private String json(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    public UploadFile errors(long id){Review r=review(id);try(InputStream in=Files.newInputStream(r.path)){List<UploadIssue> issues=r.parsed.issues().stream().map(i->new UploadIssue(i.severity().equals("WARNING")?"WARN":"ERROR",i.row(),i.column(),i.raw_value(),i.message(),null)).toList();return new UploadFile(r.name.replaceFirst("(?i)\\.xlsx$","")+"_오류.xlsx",writer.errorFile(in,issues,r.type));}catch(IOException e){throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","원본 파일을 읽을 수 없습니다.");}}
    public UploadFile template(String type){formType(type);try{return new UploadFile(type.equals("B.8")?"개발자_업로드양식.xlsx":"급여_업로드양식.xlsx",writer.template(type,mapper.codes()));}catch(IOException e){throw new IllegalStateException(e);}}
}
