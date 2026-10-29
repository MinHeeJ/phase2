package com.sicost.dispatchattendance2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import com.sicost.uploadsops.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import static com.sicost.uploadsops.PersonnelUploadModels.Issue;
import static com.sicost.uploadsops.PersonnelUploadModels.Result;
import static com.sicost.uploadsops.UploadModels.*;

@Service
@Transactional
public class DispatchAttendanceUploadService implements UploadReviewAdapter {
    private final DispatchAttendanceUploadMapper mapper;
    private final DispatchAttendanceUploadForm form;
    private final UploadService uploads;
    private final UploadWorkbookWriter writer;
    private final CurrentUser user;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private UploadMapper common;
    @org.springframework.beans.factory.annotation.Autowired private PermissionService permissions;
    @Value("${sicost.upload-directory:${java.io.tmpdir}/sicost/uploads}") private String directory;

    public DispatchAttendanceUploadService(DispatchAttendanceUploadMapper mapper,
            DispatchAttendanceUploadForm form, UploadService uploads, UploadWorkbookWriter writer,
            CurrentUser user, ObjectMapper json) {
        this.mapper=mapper; this.form=form; this.uploads=uploads;
        this.writer=writer; this.user=user; this.json=json;
    }
    private Scope scope(String month, long projectId) {
        var ym=BillingValues.month(month,"month");
        if (projectId<1) throw BillingValues.invalid("projectId");
        return new Scope(projectId,month,ym.atDay(1),ym.atEndOfMonth());
    }
    public Result validate(String month, long projectId, MultipartFile file) {
        permissions.can(BillingActions.UPLOAD);
        Scope scope=scope(month,projectId);
        String name=UploadService.filename(file.getOriginalFilename());
        if (file.getSize()>10L*1024*1024 || file.isEmpty()
                || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw BillingValues.invalid("file");
        Path path=uploads.store(file,name);
        return inspect(scope,name,path);
    }
    private Result inspect(Scope scope, String name, Path path) {
        long id=mapper.nextUploadId();
        Parsed parsed=form.parse(path,scope,mapper.project(scope.projectId()));
        Review review=new Review(id,scope,name,path.toString(),checksum(path),parsed);
        try {
            Files.createDirectories(metadata(id).getParent());
            Files.write(metadata(id),json.writeValueAsBytes(review),StandardOpenOption.CREATE_NEW);
        } catch (IOException e) { throw new IllegalStateException("검사 결과를 보관할 수 없습니다.",e); }
        Result result=summary(review,false);
        if (result.error_count()>0) {
            mapper.insertReview(new ReviewWrite(review,result,"REJECTED",user.userId()));
            saveIssues(review);
        }
        return result;
    }
    private Path metadata(long id) {
        if (id<1) throw BillingValues.invalid("upload_id");
        return Path.of(directory).toAbsolutePath().normalize().resolve("dispatch-"+id+".json");
    }
    private Path retired(long id) { return metadata(id).resolveSibling("dispatch-"+id+".retired"); }
    @Override public boolean supports(long id) { return id>0 && Files.isRegularFile(metadata(id)); }
    @Override public boolean supportsForm(String type) { return "B.3".equals(type); }
    private Review require(long id) {
        try {
            Review review=json.readValue(Files.readAllBytes(metadata(id)),Review.class);
            if (review.id()!=id) throw BillingValues.invalid("upload_id");
            return review;
        } catch (IOException e) { throw new ApiException(410,"VALIDATION_EXPIRED","검사 결과를 읽을 수 없습니다. 다시 올려 주세요.","upload_id"); }
    }
    private Result summary(Review review, boolean completed) {
        int errors=(int)review.parsed().issues().stream().filter(i->i.severity().equals("ERROR"))
            .map(Issue::row).distinct().count();
        int warnings=(int)review.parsed().rows().stream().filter(r->r.severity().equals("WARNING")).count();
        return new Result(review.id(),"B.3",review.scope().month(),review.parsed().rows().size(),errors,
            warnings,errors==0 && !review.parsed().rows().isEmpty() && !completed
                && !Files.exists(retired(review.id())),0,
            review.scope().month()+" 선택 프로젝트의 미확정 근태 전체를 새 파일로 바꿉니다. 연결 DRAFT 상세는 다시 계산 전까지 바뀌지 않습니다.",
            "/api/uploads/"+review.id()+"/issues",true);
    }
    @Override public Result result(long id) {
        permissions.can(BillingActions.UPLOAD);
        return summary(require(id),"APPLIED".equals(mapper.uploadStatus(id)));
    }
    @Override public Result revalidate(long id) {
        permissions.can(BillingActions.UPLOAD);
        Review review=require(id);
        if ("APPLIED".equals(mapper.uploadStatus(id))) throw stale();
        if (!checksum(Path.of(review.path())).equals(review.checksum())) throw BillingValues.invalid("upload_id");
        Result next=inspect(review.scope(),review.name(),Path.of(review.path()));
        try { Files.writeString(retired(id),"superseded",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING); }
        catch (IOException e) { throw new IllegalStateException(e); }
        return next;
    }
    @Override public ApplyResult apply(long id) {
        permissions.can(BillingActions.UPLOAD);
        Review review=require(id);
        Scope scope=review.scope();
        Project project=mapper.lockProject(scope.projectId());
        if (project==null) throw BillingValues.invalid("projectId");
        if ("APPLIED".equals(mapper.uploadStatus(id))) return applied(review);
        if (!summary(review,false).can_apply()) throw BillingValues.invalid("upload_id");
        BillingRules.requireOpen(BillingValues.month(scope.month(),"month"),mapper.monthStatus(scope.month()),"month");
        if (mapper.confirmedUse(scope)) throw ApiException.conflict("ATTENDANCE_BILLED",
            "확정 청구에 연결된 근태는 교체할 수 없습니다.","month");
        List<Stored> before=mapper.lockAttendance(scope);
        if (!checksum(Path.of(review.path())).equals(review.checksum())) throw BillingValues.invalid("upload_id");
        Parsed current=form.parse(Path.of(review.path()),scope,project);
        if (!current.equals(review.parsed())) throw BillingValues.invalid("upload_id");
        mapper.insertReview(new ReviewWrite(review,summary(review,false),"APPLIED",user.userId()));
        saveIssues(review);
        mapper.deleteAttendance(scope);
        for (Stored row:before) mapper.audit(new Audit(Long.toString(row.atnd_id()),"DELETE",snapshot(row),null,user.userId()));
        for (Preview row:current.rows().stream().sorted(Comparator.comparingLong(r->r.attendance().dev_id())).toList()) {
            long atndId=mapper.insertAttendance(new AttendanceWrite(scope,row.attendance(),id,user.userId()));
            mapper.audit(new Audit(Long.toString(atndId),"INSERT",null,snapshot(row.attendance()),user.userId()));
        }
        mapper.access(new UploadAccess("UPLOAD","B.3 파견 근태",snapshot(Map.of("upload_id",id,
            "projectId",scope.projectId(),"month",scope.month(),"applied_count",current.rows().size())),user.userId()));
        return applied(review);
    }
    private ApplyResult applied(Review review) {
        return new ApplyResult(review.id(),"B.3",review.parsed().rows().size(),"선택 월·프로젝트 근태를 반영했습니다.");
    }
    private void saveIssues(Review review) {
        for (Issue issue:review.parsed().issues()) common.insertIssue(new IssueWrite(review.id(),
            new UploadIssue(issue.severity().equals("WARNING")?"WARN":"ERROR",issue.row(),issue.column(),
                issue.raw_value(),issue.message(),null),issue.raw_value()));
    }
    @Override public PageResult<Issue> issues(long id, String severity, String column, DeveloperPaging paging) {
        permissions.can(BillingActions.UPLOAD);
        checkSeverity(severity,false);
        if (column!=null && !DispatchAttendanceUploadForm.HEADERS.contains(column)
                && !Set.of("파일","근태").contains(column)) throw BillingValues.invalid("column");
        var list=require(id).parsed().issues().stream().filter(i->severity==null||severity.equals(i.severity()))
            .filter(i->column==null||column.equals(i.column())).sorted(order(paging,Issue::row)).toList();
        return page(list,paging);
    }
    @Override public PageResult<Preview> rows(long id, String severity, DeveloperPaging paging) {
        permissions.can(BillingActions.UPLOAD);
        checkSeverity(severity,true);
        var list=require(id).parsed().rows().stream().filter(r->severity==null||severity.equals(r.severity()))
            .sorted(order(paging,Preview::row)).toList();
        return page(list,paging);
    }
    private static void checkSeverity(String severity, boolean rows) {
        if (severity!=null && !(rows?Set.of("NORMAL","WARNING","ERROR"):Set.of("WARNING","ERROR")).contains(severity))
            throw BillingValues.invalid("severity");
    }
    private static <T> Comparator<T> order(DeveloperPaging paging, java.util.function.ToIntFunction<T> row) {
        String sort=paging.sort();
        if (sort!=null && !Set.of("row,asc","row,desc").contains(sort)) throw BillingValues.invalid("sort");
        Comparator<T> comparator=Comparator.comparingInt(row);
        return "row,desc".equals(sort)?comparator.reversed():comparator;
    }
    private static <T> PageResult<T> page(List<T> list, DeveloperPaging paging) {
        long offset=(long)paging.page()*paging.size();
        int from=(int)Math.min(offset,list.size()),to=(int)Math.min(offset+paging.size(),list.size());
        return paging.result(List.copyOf(list.subList(from,to)),list.size());
    }
    @Override public UploadFile errors(long id) {
        permissions.can(BillingActions.DOWNLOAD);
        Review review=require(id);
        List<UploadIssue> issues=review.parsed().issues().stream().map(i->new UploadIssue(
            i.severity().equals("WARNING")?"WARN":"ERROR",i.row(),i.column(),i.raw_value(),i.message(),null)).toList();
        try (InputStream in=Files.newInputStream(Path.of(review.path()))) {
            return new UploadFile("파견근태_오류.xlsx",writer.errorFile(in,issues,DispatchAttendanceUploadForm.HEADERS));
        } catch (IOException | RuntimeException e) {
            try (var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out=new ByteArrayOutputStream()) {
                var sheet=book.createSheet("오류 목록");
                for (int n=0;n<issues.size();n++) sheet.createRow(n).createCell(0).setCellValue(issues.get(n).message());
                book.write(out);
                return new UploadFile("파견근태_오류.xlsx",out.toByteArray());
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
    }
    @Override public UploadFile template() {
        permissions.can(BillingActions.DOWNLOAD);
        try (var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("파견 근태");
            var title=sheet.createRow(3);
            for (int i=0;i<DispatchAttendanceUploadForm.HEADERS.size();i++) {
                title.createCell(i).setCellValue(DispatchAttendanceUploadForm.HEADERS.get(i));
                sheet.setColumnWidth(i,24*256);
            }
            sheet.createRow(0).createCell(0).setCellValue("B.3 · 선택한 연월·프로젝트ID와 일치하는 근태를 입력하세요. 오류가 있으면 전체 미반영합니다.");
            sheet.createFreezePane(0,4);
            book.write(out);
            return new UploadFile("파견근태_업로드양식.xlsx",out.toByteArray());
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static ApiException stale() { return ApiException.conflict("ALREADY_APPLIED","이미 반영한 파일입니다.","upload_id"); }
    private String snapshot(Object value) {
        try { return json.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static String checksum(Path path) {
        try (InputStream in=Files.newInputStream(path)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[8192];
            for (int n;(n=in.read(buffer))!=-1;) digest.update(buffer,0,n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) { throw BillingValues.invalid("file"); }
    }
}
