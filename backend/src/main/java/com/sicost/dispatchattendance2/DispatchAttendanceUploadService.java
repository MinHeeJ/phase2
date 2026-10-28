package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.persistence.AssignmentConcurrencyService;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.BillingRules;
import com.sicost.uploadsops.*;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.PersonnelUploadModels.Result;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import com.sicost.uploadsops.UploadModels.UploadAccess;
import com.sicost.uploadsops.UploadModels.UploadIssue;
import com.sicost.uploadsops.UploadModels.IssueWrite;
import com.sicost.uploadsops.UploadModels.UploadFile;

@Service
public class DispatchAttendanceUploadService implements UploadReviewProvider {
    private final DispatchAttendanceUploadMapper mapper;
    private final DispatchAttendanceUploadForm form;
    private final UploadService storage;
    private final UploadWorkbookWriter writer;
    private final Clock clock;
    private final TransactionTemplate transaction;
    @Autowired private PersonnelUploadMapper allocation;
    @Autowired private UploadMapper uploads;
    @Autowired private CurrentUser user;
    @Autowired private ObjectMapper json;
    @Autowired private UploadInspectionContexts inspections;
    @Autowired private AssignmentConcurrencyService concurrency;
    private final Map<Long,Review> reviews = new HashMap<>();
    private static final class Review {
        final long id;
        final Scope scope;
        final String name;
        final Path path;
        final Parsed parsed;
        final byte[] digest;
        final Instant validatedAt;
        boolean retired;
        Application completed;
        Review(long id, Scope scope, String name, Path path, Parsed parsed, byte[] digest, Instant now) {
            this.id=id; this.scope=scope; this.name=name; this.path=path; this.parsed=parsed;
            this.digest=digest; this.validatedAt=now;
        }
    }
    public DispatchAttendanceUploadService(DispatchAttendanceUploadMapper mapper, DispatchAttendanceUploadForm form,
            UploadService storage, UploadWorkbookWriter writer, Clock clock, PlatformTransactionManager manager) {
        this.mapper=mapper; this.form=form; this.storage=storage; this.writer=writer;
        this.clock=clock; this.transaction=new TransactionTemplate(manager);
    }
    private Scope scope(long projectId, String month) {
        if (projectId < 1) throw BillingRules.invalid("projectId");
        var ym=BillingRules.month(month,"month");
        return new Scope(projectId,month,ym.atDay(1),ym.atEndOfMonth());
    }
    private Project project(Scope scope) {
        Project p=mapper.project(scope.projectId());
        if (p==null) throw ApiException.notFound("프로젝트가 없습니다.");
        if (!"DP".equals(p.type()) || !("DAY".equals(p.settlement()) || "HOUR".equals(p.settlement()))
                || !"Y".equals(p.enabled())) throw BillingRules.invalid("projectId");
        return p;
    }
    private void open(Scope scope) {
        BillingRules.openMonth(scope.month(),mapper.monthStatus(scope.month()));
        if (mapper.confirmedUsage(scope)) throw ApiException.conflict("ATTENDANCE_BILLED",
            "확정 청구에 사용된 근태가 있어 선택 범위를 교체할 수 없습니다.","month");
    }
    private Parsed parse(Path path, Scope scope, Project project) {
        try (InputStream input=Files.newInputStream(path)) { return form.parse(input,scope,project); }
        catch (IOException ex) { throw DispatchAttendanceUploadForm.fileError("UNSUPPORTED_FILE_TYPE","원본 파일을 다시 올려 주세요."); }
    }
    private byte[] digest(Path path) {
        try (InputStream in=Files.newInputStream(path)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[8192];
            int length;
            while ((length=in.read(buffer))!=-1) digest.update(buffer,0,length);
            return digest.digest();
        } catch (IOException ex) { throw DispatchAttendanceUploadForm.fileError("UNSUPPORTED_FILE_TYPE","원본 파일을 읽을 수 없습니다."); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public Result validate(MultipartFile file, String month, long projectId) {
        Scope scope=scope(projectId,month);
        Project p=project(scope);
        open(scope);
        String name=UploadService.filename(file.getOriginalFilename());
        Path path=storage.store(file,name);
        return inspect(scope,name,path,p);
    }
    private Result inspect(Scope scope, String name, Path path, Project project) {
        byte[] hash=digest(path);
        Parsed parsed=parse(path,scope,project);
        if (!MessageDigest.isEqual(hash,digest(path))) throw stale();
        long id=allocation.nextUploadId();
        var review=new Review(id,scope,name,path,parsed,hash,clock.instant());
        Result result=result(review);
        if (result.error_count()>0) transaction.executeWithoutResult(status -> {
            mapper.insertUpload(upload(review,result,"REJECTED","N"));
            saveIssues(review);
        });
        inspections.publish(new UploadInspectionContexts.Inspection(id,"B.3",scope.month(),result.row_count(),
            result.error_count(),result.warning_count(),result.can_apply(),true),review.validatedAt);
        synchronized (reviews) {
            reviews.entrySet().removeIf(e -> !clock.instant().isBefore(e.getValue().validatedAt.plusSeconds(1800)));
            reviews.put(id,review);
        }
        return result;
    }
    @Override public boolean supports(String type) { return "B.3".equals(type); }
    @Override public boolean owns(long id) { synchronized (reviews) { return reviews.containsKey(id); } }
    private Review review(long id) {
        if (id<1) throw BillingRules.invalid("upload_id");
        Review r;
        synchronized (reviews) { r=reviews.get(id); }
        if (r==null || !clock.instant().isBefore(r.validatedAt.plusSeconds(1800))) {
            throw new ApiException(410,"VALIDATION_EXPIRED","검사 후 30분이 지났습니다. 다시 검사해 주세요.","upload_id");
        }
        return r;
    }
    private Result result(Review r) {
        int errors=(int)r.parsed.rows().stream().filter(v->v.severity().equals("ERROR")).count();
        int warnings=(int)r.parsed.rows().stream().filter(v->v.severity().equals("WARNING")).count();
        return new Result(r.id,"B.3",r.scope.month(),r.parsed.rows().size(),errors,warnings,
            errors==0 && !r.retired && r.completed==null,0,
            r.scope.month()+" 선택 프로젝트의 미확정 근태 전체를 새 파일로 바꿉니다. 연결 DRAFT는 다시 계산 전까지 유지됩니다.",
            "/api/uploads/"+r.id+"/issues",true);
    }
    public Result result(long id) { return result(review(id)); }
    @Override public Result revalidate(long id) {
        Review r=review(id);
        synchronized (r) {
            if (r.completed!=null) throw ApiException.conflict("ALREADY_APPLIED","이미 반영한 파일입니다.","upload_id");
            open(r.scope);
            Result next=inspect(r.scope,r.name,r.path,project(r.scope));
            r.retired=true;
            inspections.invalidate(id);
            return next;
        }
    }
    public Application apply(Apply input) {
        if (input==null || !input.confirm()) throw BillingRules.invalid("confirm");
        Review r=review(input.upload_id());
        synchronized (r) {
            if (r.completed!=null) return r.completed;
            if (r.retired) throw stale();
            if (!result(r).can_apply()) throw BillingRules.invalid("upload_id");
            Application applied=transaction.execute(status -> {
                concurrency.lockProject(r.scope.projectId());
                Project p=project(r.scope);
                var before=mapper.lockAttendance(r.scope);
                open(r.scope);
                if (!MessageDigest.isEqual(r.digest,digest(r.path))) throw stale();
                Parsed current=parse(r.path,r.scope,p);
                if (!current.equals(r.parsed) || !MessageDigest.isEqual(r.digest,digest(r.path))) throw stale();
                mapper.insertUpload(upload(r,result(r),"APPLIED",before.isEmpty()?"N":"Y"));
                mapper.deleteAttendance(r.scope);
                Map<Long,Attendance> previous=new LinkedHashMap<>();
                before.forEach(row -> previous.put(row.dev_id(),row));
                for (Attendance row:current.attendance()) {
                    long id=mapper.insertAttendance(new AttendanceWrite(row,r.id,user.userId()));
                    Attendance saved=new Attendance(id,row.work_ym(),row.prj_id(),row.dev_id(),row.asgn_id(),
                        row.work_day_qty(),row.rglr_tm_qty(),row.ovtm_tm_qty(),row.mth_work_day_qty(),r.id);
                    Attendance old=previous.remove(row.dev_id());
                    mapper.audit(new Audit(Long.toString(id),old==null?"INSERT":"UPDATE",snapshot(old),snapshot(saved),user.userId()));
                }
                for (Attendance old:previous.values()) mapper.audit(new Audit(Long.toString(old.atnd_id()),
                    "DELETE",snapshot(old),null,user.userId()));
                saveIssues(r);
                mapper.access(new UploadAccess("UPLOAD",r.name.substring(0,Math.min(200,r.name.length())),
                    snapshot(Map.of("upload_id",r.id,"projectId",r.scope.projectId(),"month",r.scope.month())),user.userId()));
                return new Application(r.id,"B.3",current.attendance().size(),"선택 월·프로젝트의 근태를 반영했습니다. 연결 DRAFT는 다시 계산해 주세요.");
            });
            r.completed=Objects.requireNonNull(applied);
            inspections.invalidate(r.id);
            return applied;
        }
    }
    private UploadWrite upload(Review r, Result result, String status, String overwrite) {
        return new UploadWrite(r.id,r.scope,r.name,r.path.toString(),result,status,overwrite,user.userId());
    }
    private void saveIssues(Review r) {
        for (Issue issue:r.parsed.issues()) {
            String raw=issue.raw_value();
            var value=new UploadIssue(issue.severity().equals("WARNING")?"WARN":"ERROR",issue.row(),
                issue.column(),raw,issue.message(),null);
            uploads.insertIssue(new IssueWrite(r.id,value,raw==null?null:raw.substring(0,Math.min(500,raw.length()))));
        }
    }
    private String snapshot(Object value) {
        if (value==null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private static ApiException stale() {
        return ApiException.conflict("STALE_VALIDATION","검사 결과 또는 원본이 변경되었습니다. 다시 검사해 주세요.","upload_id");
    }
    private static boolean descending(DeveloperPaging paging) {
        if (!Set.of("row,asc","row,desc").contains(paging.sort())) throw BillingRules.invalid("sort");
        return paging.sort().equals("row,desc");
    }
    private static <T> PageResult<T> page(List<T> rows, DeveloperPaging p) {
        long offset=(long)p.page()*p.size();
        int from=(int)Math.min(rows.size(),offset);
        return p.result(List.copyOf(rows.subList(from,(int)Math.min(rows.size(),offset+p.size()))),rows.size());
    }
    @Override public PageResult<Issue> issues(long id, String severity, String column, DeveloperPaging paging) {
        Review r=review(id);
        if (severity!=null && !Set.of("ERROR","WARNING").contains(severity)) throw BillingRules.invalid("severity");
        if (column!=null && !PersonnelUploadForm.ATTENDANCE_HEADERS.contains(column) && !"파일".equals(column)) throw BillingRules.invalid("column");
        Comparator<Issue> order=Comparator.comparingInt(Issue::row);
        if (descending(paging)) order=order.reversed();
        var rows=r.parsed.issues().stream().filter(i->severity==null || severity.equals(i.severity()))
            .filter(i->column==null || column.equals(i.column())).sorted(order).toList();
        return page(rows,paging);
    }
    @Override public PageResult<Preview> rows(long id, String severity, DeveloperPaging paging) {
        Review r=review(id);
        if (severity!=null && !Set.of("NORMAL","WARNING","ERROR").contains(severity)) throw BillingRules.invalid("severity");
        Comparator<Preview> order=Comparator.comparingInt(Preview::row);
        if (descending(paging)) order=order.reversed();
        return page(r.parsed.rows().stream().filter(i->severity==null || severity.equals(i.severity())).sorted(order).toList(),paging);
    }
    @Override public UploadFile errors(long id) {
        Review r=review(id);
        try (InputStream in=Files.newInputStream(r.path)) {
            var issues=r.parsed.issues().stream().map(i->new UploadIssue(i.severity().equals("WARNING")?"WARN":"ERROR",
                i.row(),i.column(),i.raw_value(),i.message(),null)).toList();
            return new UploadFile(r.name.replaceFirst("(?i)\\.xlsx$","")+"_오류.xlsx",writer.errorFile(in,issues,"B.3"));
        } catch (IOException ex) { throw DispatchAttendanceUploadForm.fileError("UNSUPPORTED_FILE_TYPE","원본 파일을 읽을 수 없습니다."); }
    }
    @Override public UploadFile template() {
        try { return new UploadFile("파견근태_업로드양식.xlsx",writer.template("B.3",Collections.emptyList())); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
    }
}
