package com.sicost.financeentries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.entries.*;
import com.sicost.entries.FinanceEntryModels.EntryAudit;
import com.sicost.entries.FinanceEntryModels.EntryRemoval;
import com.sicost.uploadsops.*;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.PersonnelUploadModels.Result;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.financeentries.FinanceEntriesUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

@Service
@Transactional
public class FinanceEntriesUploadService implements UploadReviewAdapter {
    private final FinanceEntriesUploadMapper mapper;
    private final FinanceEntriesUploadForm form;
    private final UploadService uploads;
    private final UploadWorkbookWriter writer;
    private final CurrentUser user;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private FinanceEntryMapper entries;
    @org.springframework.beans.factory.annotation.Autowired private UploadMapper common;
    @org.springframework.beans.factory.annotation.Autowired private PermissionService permissions;
    @org.springframework.beans.factory.annotation.Autowired private Clock clock;
    @Value("${sicost.upload-directory:${java.io.tmpdir}/sicost/uploads}") private String directory;
    public FinanceEntriesUploadService(FinanceEntriesUploadMapper mapper,FinanceEntriesUploadForm form,
            UploadService uploads,UploadWorkbookWriter writer,CurrentUser user,ObjectMapper json) {
        this.mapper=mapper;this.form=form;this.uploads=uploads;this.writer=writer;this.user=user;this.json=json;
    }
    @Override public String permissionKey(String action) {return "UI-10."+("download".equals(action)?"download":"upload");}
    public Summary validate(MultipartFile file) {
        permissions.can(permissionKey("upload"));
        if(file==null || file.isEmpty() || file.getSize()>10L*1024*1024 || file.getOriginalFilename()==null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw FinanceEntryInput.invalid("file");
        String name=UploadService.filename(file.getOriginalFilename());return inspect(name,uploads.store(file,name));
    }
    private Summary inspect(String name,Path path) {
        long id=mapper.nextUploadId();Parsed parsed=check(form.parse(path));
        Review review=new Review(id,parsed.month(),name,path.toString(),checksum(path),parsed);
        try {Files.createDirectories(metadata(id).getParent());Files.write(metadata(id),json.writeValueAsBytes(review),StandardOpenOption.CREATE_NEW);}
        catch(IOException e) {throw new IllegalStateException("검사 결과를 보관할 수 없습니다.",e);}
        Summary result=summary(review,0,false);
        if(result.error_count()>0) {mapper.saveReview(new ReviewWrite(review,result,"REJECTED",user.userId()));saveIssues(review);}
        return result;
    }
    private void open(String month) {
        var ym=FinanceEntryInput.month(month,"entry_ym");
        if(ym.isAfter(YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")))))
            throw ApiException.conflict("INVALID_MONTH","미래월은 선택할 수 없습니다.","entry_ym");
        if("CLOSED".equals(entries.monthStatus(month)))throw ApiException.conflict("CLOSED_MONTH","마감된 달의 항목을 교체할 수 없습니다.","entry_ym");
    }
    private Parsed check(Parsed parsed) {
        var issues=new ArrayList<>(parsed.issues());var rows=new ArrayList<Preview>();
        for(var preview:parsed.rows()) {
            String severity=preview.severity();
            if(preview.entry()!=null)try {
                open(preview.entry().entry_ym());
                if(preview.entry().inout_dt()!=null)open(YearMonth.from(preview.entry().inout_dt()).toString());
            } catch(ApiException e) {
                FinanceEntriesUploadForm.issue(issues,preview.row(),"연월",e.getMessage(),"ERROR");severity="ERROR";
            }
            rows.add(new Preview(preview.row(),severity,preview.entry()));
        }
        return new Parsed(parsed.month(),List.copyOf(rows),List.copyOf(issues));
    }
    private Path metadata(long id) {
        if(id<1)throw FinanceEntryInput.invalid("upload_id");
        return Path.of(directory).toAbsolutePath().normalize().resolve("finance-entries-"+id+".json");
    }
    private Path retired(long id) {return metadata(id).resolveSibling("finance-entries-"+id+".retired");}
    @Override public boolean supports(long id) {return id>0 && Files.isRegularFile(metadata(id));}
    @Override public boolean supportsForm(String form) {return "B.2".equals(form);}
    private Review require(long id) {
        if(!supports(id))throw ApiException.notFound("재무 항목 검사 결과가 없습니다. 다시 올려 주세요.");
        try {var review=json.readValue(Files.readAllBytes(metadata(id)),Review.class);
            if(review.id()!=id)throw FinanceEntryInput.invalid("upload_id");return review;
        } catch(IOException e) {throw ApiException.notFound("검사 결과를 읽을 수 없습니다. 다시 올려 주세요.");}
    }
    private Summary summary(Review review,int applied,boolean completed) {
        int errors=(int)review.parsed().issues().stream().filter(i->"ERROR".equals(i.severity())).map(Issue::row).distinct().count();
        int warnings=(int)review.parsed().issues().stream().filter(i->"WARNING".equals(i.severity())).map(Issue::row).distinct().count();
        int replaced=review.month()==null?0:entries.list(review.month()).size();
        var items=review.parsed().issues().stream().map(i->new Item(i.column(),i.message(),i.row(),i.severity(),i.raw_value())).toList();
        return new Summary(Long.toString(review.id()),errors,warnings,items,review.parsed().rows().size(),
            errors==0 && !review.parsed().rows().isEmpty() && !completed && !Files.exists(retired(review.id())),
            applied,replaced,review.parsed().rows(),"해당 연월의 저장된 항목 "+replaced+"건(수기 포함) 전체를 새 파일로 교체합니다. 저장 전 초안과 변경 이력은 유지됩니다.");
    }
    public Summary apply(long id,boolean confirm) {
        permissions.can(permissionKey("upload"));Review review=require(id);
        if(confirm)mapper.lockScope();
        if("APPLIED".equals(mapper.uploadStatus(id)))return summary(review,review.parsed().rows().size(),true);
        if(!summary(review,0,false).can_apply())throw FinanceEntryInput.invalid("upload_id");
        if(!checksum(Path.of(review.path())).equals(review.checksum()))throw FinanceEntryInput.invalid("file");
        open(review.month());
        Parsed current=check(form.parse(Path.of(review.path())));
        for(var row:current.rows())if(row.entry()!=null && row.entry().inout_dt()!=null)open(YearMonth.from(row.entry().inout_dt()).toString());
        if(!current.equals(review.parsed()))throw FinanceEntryInput.invalid("upload_id");
        var before=entries.list(review.month());
        for(var row:before)if(row.inout_dt()!=null)open(YearMonth.from(row.inout_dt()).toString());
        if(!confirm)return summary(review,0,false);
        Summary result=summary(review,0,false);
        mapper.saveReview(new ReviewWrite(review,result,"APPLIED",user.userId()));saveIssues(review);
        for(var row:before) {
            if(entries.delete(new EntryRemoval(row.entry_id(),user.userId()))!=1)throw ApiException.conflict("STALE_PAYMENT","항목이 변경되었습니다. 다시 검사하세요.","upload_id");
            entries.audit(new EntryAudit(Long.toString(row.entry_id()),"DELETE",snapshot(row),null,user.userId()));
        }
        for(var preview:current.rows()) {
            long entryId=mapper.insert(new EntryWrite(preview.entry(),id,user.userId()));
            entries.audit(new EntryAudit(Long.toString(entryId),"INSERT",null,snapshot(preview.entry()),user.userId()));
        }
        mapper.access(new UploadAccess("UPLOAD","B.2 재무 항목",snapshot(Map.of("upload_id",id,"month",review.month(),"applied_count",current.rows().size())),user.userId()));
        return new Summary(result.upload_id(),0,result.warning_count(),result.items(),result.row_count(),false,
            current.rows().size(),result.overwrite_count(),result.rows(),result.overwrite_notice());
    }
    private void saveIssues(Review review) {
        for(Issue issue:review.parsed().issues())common.insertIssue(new IssueWrite(review.id(),new UploadIssue(
            "WARNING".equals(issue.severity())?"WARN":"ERROR",issue.row(),issue.column(),issue.raw_value(),issue.message(),null),issue.raw_value()));
    }
    @Override public Result result(long id) {
        permissions.can(permissionKey("upload"));var review=require(id);var s=summary(review,0,"APPLIED".equals(mapper.uploadStatus(id)));
        return new Result(id,"B.2",review.month(),s.row_count(),s.error_count(),s.warning_count(),s.can_apply(),s.overwrite_count(),
            s.overwrite_notice(),"/api/uploads/"+id+"/issues",true);
    }
    @Override public Result revalidate(long id) {
        permissions.can(permissionKey("upload"));var review=require(id);
        if("APPLIED".equals(mapper.uploadStatus(id)))throw FinanceEntryInput.invalid("upload_id");
        if(!checksum(Path.of(review.path())).equals(review.checksum()))throw FinanceEntryInput.invalid("file");
        var next=inspect(review.name(),Path.of(review.path()));
        try {Files.writeString(retired(id),"superseded");}catch(IOException e) {throw new IllegalStateException(e);}
        return result(Long.parseLong(next.upload_id()));
    }
    @Override public Object apply(long id) {return apply(id,true);}
    @Override public PageResult<Issue> issues(long id,String severity,String column,DeveloperPaging paging) {
        permissions.can(permissionKey("upload"));checkSeverity(severity);
        if(column!=null && !FinanceEntriesUploadForm.HEADERS.contains(column) && !column.equals("파일"))throw FinanceEntryInput.invalid("column");
        return page(require(id).parsed().issues().stream().filter(i->severity==null||severity.equals(i.severity()))
            .filter(i->column==null||column.equals(i.column())).toList(),paging);
    }
    @Override public Object rows(long id,String severity,DeveloperPaging paging) {
        permissions.can(permissionKey("upload"));checkSeverity(severity);
        return page(require(id).parsed().rows().stream().filter(r->severity==null||severity.equals(r.severity())).toList(),paging);
    }
    private static void checkSeverity(String severity) {
        if(severity!=null && !Set.of("NORMAL","WARNING","ERROR").contains(severity))throw FinanceEntryInput.invalid("severity");
    }
    private static <T> PageResult<T> page(List<T> input,DeveloperPaging paging) {
        if(paging.sort()!=null && !Set.of("row,asc","row,desc").contains(paging.sort()))throw FinanceEntryInput.invalid("sort");
        var list=new ArrayList<>(input);if("row,desc".equals(paging.sort()))Collections.reverse(list);
        int from=(int)Math.min((long)paging.page()*paging.size(),list.size()),to=Math.min(from+paging.size(),list.size());
        return paging.result(List.copyOf(list.subList(from,to)),list.size());
    }
    @Override public UploadFile errors(long id) {
        permissions.can(permissionKey("download"));var review=require(id);
        var issues=review.parsed().issues().stream().map(i->new UploadIssue("WARNING".equals(i.severity())?"WARN":"ERROR",i.row(),i.column(),i.raw_value(),i.message(),null)).toList();
        UploadFile file;
        try(InputStream in=Files.newInputStream(Path.of(review.path()))) {
            file=new UploadFile("재무항목_오류.xlsx",writer.errorFile(in,issues,FinanceEntriesUploadForm.HEADERS));
        } catch(IOException|RuntimeException e) {file=workbook("재무항목_오류.xlsx",issues.stream().map(UploadIssue::message).toList(),false);}
        mapper.access(new UploadAccess("DOWNLOAD","B.2 오류 파일",snapshot(Map.of("upload_id",id)),user.userId()));return file;
    }
    @Override public UploadFile template() {
        permissions.can(permissionKey("download"));
        var file=workbook("재무항목_업로드양식.xlsx",List.of("B.2 · 연월·구분·항목·금액 필수. 한 연월만 입력하며 오류가 있으면 전체 미반영합니다."),true);
        mapper.access(new UploadAccess("DOWNLOAD","B.2 업로드 양식",snapshot(Map.of("form","B.2")),user.userId()));return file;
    }
    private static UploadFile workbook(String name,List<String> messages,boolean template) {
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("재무 항목");for(int n=0;n<messages.size();n++)sheet.createRow(n).createCell(0).setCellValue(messages.get(n));
            if(template) {var title=sheet.createRow(3);for(int c=0;c<FinanceEntriesUploadForm.HEADERS.size();c++) {
                title.createCell(c).setCellValue(FinanceEntriesUploadForm.HEADERS.get(c));sheet.setColumnWidth(c,24*256);}
                sheet.createFreezePane(0,4);}
            book.write(out);return new UploadFile(name,out.toByteArray());
        } catch(IOException e) {throw new IllegalStateException(e);}
    }
    private String snapshot(Object value) {
        try {return json.writeValueAsString(value);}catch(IOException e) {throw new IllegalStateException(e);}
    }
    private static String checksum(Path path) {
        try(InputStream in=Files.newInputStream(path)) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];
            for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);return HexFormat.of().formatHex(digest.digest());
        } catch(IOException|NoSuchAlgorithmException e) {throw FinanceEntryInput.invalid("file");}
    }
}
