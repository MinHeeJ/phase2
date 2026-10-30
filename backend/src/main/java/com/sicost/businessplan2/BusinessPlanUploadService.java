package com.sicost.businessplan2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.uploadsops.*;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.PersonnelUploadModels.Result;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.businessplan2.BusinessPlanUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

@Service
@Transactional
public class BusinessPlanUploadService implements UploadReviewAdapter {
    private final BusinessPlanUploadMapper mapper;
    private final BusinessPlanUploadForm form;
    private final UploadService uploads;
    private final UploadWorkbookWriter writer;
    private final CurrentUser user;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private UploadMapper common;
    @org.springframework.beans.factory.annotation.Autowired private PermissionService permissions;
    @Value("${sicost.upload-directory:${java.io.tmpdir}/sicost/uploads}") private String directory;
    public BusinessPlanUploadService(BusinessPlanUploadMapper mapper,BusinessPlanUploadForm form,
            UploadService uploads,UploadWorkbookWriter writer,CurrentUser user,ObjectMapper json) {
        this.mapper=mapper;this.form=form;this.uploads=uploads;this.writer=writer;this.user=user;this.json=json;
    }
    @Override public String permissionKey(String action) {return "UI-18."+("download".equals(action)?"download":"upload");}
    public Summary validate(MultipartFile file) {
        permissions.can(permissionKey("upload"));
        if(file==null || file.isEmpty() || file.getSize()>10L*1024*1024
                || file.getOriginalFilename()==null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw BusinessPlanUploadInput.invalid("file");
        String name=UploadService.filename(file.getOriginalFilename());
        return inspect(name,uploads.store(file,name));
    }
    private Summary inspect(String name,Path path) {
        long id=mapper.nextUploadId();Parsed parsed=check(form.parse(path));
        Review review=new Review(id,parsed.year(),name,path.toString(),checksum(path),parsed);
        try {Files.createDirectories(metadata(id).getParent());Files.write(metadata(id),json.writeValueAsBytes(review),StandardOpenOption.CREATE_NEW);}
        catch(IOException e) {throw new IllegalStateException("검사 결과를 보관할 수 없습니다.",e);}
        Summary summary=summary(review,0,false);
        if(summary.error_count()>0) {
            mapper.saveReview(new ReviewWrite(review,summary,"REJECTED",user.userId()));saveIssues(review);
        }
        return summary;
    }
    private Parsed check(Parsed parsed) {
        if(parsed.year()==null) return parsed;
        var closed=new HashSet<>(mapper.closedMonths(parsed.year()));
        var existing=mapper.plans(parsed.year());var issues=new ArrayList<>(parsed.issues());
        var rows=new ArrayList<Preview>();
        for(Preview preview:parsed.rows()) {
            Plan row=preview.plan();boolean mismatch=false;
            if(row!=null && closed.contains(row.month())) {
                mismatch=existing.stream().noneMatch(p->same(p,row));
                if(mismatch) BusinessPlanUploadForm.error(issues,preview.row(),"월","마감된 월의 목표와 다릅니다.");
            }
            rows.add(new Preview(preview.row(),mismatch?"ERROR":preview.severity(),row));
        }
        return new Parsed(parsed.year(),List.copyOf(rows),List.copyOf(issues));
    }
    private static boolean same(Plan a,Plan b) {
        return a.month().equals(b.month()) && Objects.equals(a.partId(),b.partId())
            && a.revenue()==b.revenue() && a.profit()==b.profit() && a.utilization()!=null
            && a.utilization().compareTo(b.utilization())==0;
    }
    private Path metadata(long id) {
        if(id<1) throw BusinessPlanUploadInput.invalid("upload_id");
        return Path.of(directory).toAbsolutePath().normalize().resolve("business-plan-"+id+".json");
    }
    private Path retired(long id) {return metadata(id).resolveSibling("business-plan-"+id+".retired");}
    @Override public boolean supports(long id) {return id>0 && Files.isRegularFile(metadata(id));}
    @Override public boolean supportsForm(String form) {return "B.10".equals(form);}
    private Review require(long id) {
        if(!supports(id)) throw ApiException.notFound("사업계획 검사 결과가 없습니다. 다시 올려 주세요.");
        try {var review=json.readValue(Files.readAllBytes(metadata(id)),Review.class);
            if(review.id()!=id) throw BusinessPlanUploadInput.invalid("upload_id");return review;
        } catch(IOException e) {throw ApiException.notFound("사업계획 검사 결과를 읽을 수 없습니다.");}
    }
    private Summary summary(Review review,int applied,boolean completed) {
        int count=(int)review.parsed().issues().stream().filter(i->"ERROR".equals(i.severity())).map(Issue::row).distinct().count();
        var items=review.parsed().issues().stream().map(i->new Item(i.column(),i.message(),i.row(),i.severity())).toList();
        return new Summary(Long.toString(review.id()),count,items,review.parsed().rows().size(),
            count==0 && !review.parsed().rows().isEmpty() && !completed && !Files.exists(retired(review.id())),
            applied,review.parsed().rows(),"해당 연도 사업계획 중 마감 월은 유지하고 미마감 월 전체를 새 파일로 교체합니다.");
    }
    public Summary apply(long id,boolean confirm) {
        permissions.can(permissionKey("upload"));
        Review review=require(id);
        if(confirm) mapper.lockScope();
        boolean completed="APPLIED".equals(mapper.uploadStatus(id));
        if(completed) return summary(review,(int)review.parsed().rows().stream()
            .filter(r->r.plan()!=null && !mapper.closedMonths(review.year()).contains(r.plan().month())).count(),true);
        if(!summary(review,0,false).can_apply()) throw BusinessPlanUploadInput.invalid("upload_id");
        if(!checksum(Path.of(review.path())).equals(review.checksum())) throw BusinessPlanUploadInput.invalid("file");
        Parsed current=check(form.parse(Path.of(review.path())));
        if(current.issues().stream().anyMatch(i->i.message().equals("마감된 월의 목표와 다릅니다.")))
            throw ApiException.conflict("CLOSED_MONTH","마감된 월의 사업계획을 변경할 수 없습니다.","month");
        if(!current.equals(review.parsed())) throw BusinessPlanUploadInput.invalid("upload_id");
        if(!confirm) return summary(review,0,false);
        Set<String> closed=new HashSet<>(mapper.closedMonths(review.year()));
        var before=mapper.plans(review.year());
        mapper.saveReview(new ReviewWrite(review,summary(review,0,false),"APPLIED",user.userId()));saveIssues(review);
        for(Plan row:before) if(!closed.contains(row.month())) {
            mapper.deleteOpen(row);mapper.audit(new Audit(Long.toString(row.id()),"DELETE",snapshot(row),null,user.userId()));
        }
        int applied=0;
        for(Preview preview:current.rows()) if(!closed.contains(preview.plan().month())) {
            Plan row=preview.plan();long planId=mapper.replace(new PlanWrite(row,id,user.userId()));
            mapper.audit(new Audit(Long.toString(planId),"INSERT",null,snapshot(row),user.userId()));applied++;
        }
        common.access(new UploadAccess("UPLOAD","B.10 사업계획",snapshot(Map.of("upload_id",id,"year",review.year(),"applied_count",applied)),user.userId()));
        return summary(review,applied,true);
    }
    private void saveIssues(Review review) {
        for(Issue issue:review.parsed().issues()) common.insertIssue(new IssueWrite(review.id(),
            new UploadIssue("ERROR",issue.row(),issue.column(),null,issue.message(),null),null));
    }
    @Override public Result result(long id) {
        permissions.can(permissionKey("upload"));Review review=require(id);Summary summary=summary(review,0,"APPLIED".equals(mapper.uploadStatus(id)));
        return new Result(id,"B.10",null,summary.row_count(),summary.error_count(),0,summary.can_apply(),0,
            summary.overwrite_notice(),"/api/uploads/"+id+"/issues",true);
    }
    @Override public Result revalidate(long id) {
        permissions.can(permissionKey("upload"));Review review=require(id);
        if("APPLIED".equals(mapper.uploadStatus(id))) throw BusinessPlanUploadInput.invalid("upload_id");
        if(!checksum(Path.of(review.path())).equals(review.checksum())) throw BusinessPlanUploadInput.invalid("file");
        Summary next=inspect(review.name(),Path.of(review.path()));
        try {Files.writeString(retired(id),"superseded");} catch(IOException e) {throw new IllegalStateException(e);}
        return result(Long.parseLong(next.upload_id()));
    }
    @Override public Object apply(long id) {return apply(id,true);}
    @Override public PageResult<Issue> issues(long id,String severity,String column,DeveloperPaging paging) {
        permissions.can(permissionKey("upload"));checkSeverity(severity);
        if(column!=null && !BusinessPlanUploadForm.HEADERS.contains(column) && !column.equals("파일")) throw BusinessPlanUploadInput.invalid("column");
        var list=require(id).parsed().issues().stream().filter(i->severity==null||severity.equals(i.severity()))
            .filter(i->column==null||column.equals(i.column())).toList();return page(list,paging);
    }
    @Override public Object rows(long id,String severity,DeveloperPaging paging) {
        permissions.can(permissionKey("upload"));checkSeverity(severity);
        return page(require(id).parsed().rows().stream().filter(i->severity==null||severity.equals(i.severity())).toList(),paging);
    }
    private static void checkSeverity(String severity) {
        if(severity!=null && !Set.of("NORMAL","WARNING","ERROR").contains(severity)) throw BusinessPlanUploadInput.invalid("severity");
    }
    private static <T> PageResult<T> page(List<T> input,DeveloperPaging paging) {
        if(paging.sort()!=null && !Set.of("row,asc","row,desc").contains(paging.sort())) throw BusinessPlanUploadInput.invalid("sort");
        var list=new ArrayList<>(input);if("row,desc".equals(paging.sort())) Collections.reverse(list);
        int from=(int)Math.min((long)paging.page()*paging.size(),list.size()),to=Math.min(from+paging.size(),list.size());
        return paging.result(List.copyOf(list.subList(from,to)),list.size());
    }
    @Override public UploadFile errors(long id) {
        permissions.can(permissionKey("download"));Review review=require(id);
        var issues=review.parsed().issues().stream().map(i->new UploadIssue("ERROR",i.row(),i.column(),null,i.message(),null)).toList();
        try(InputStream in=Files.newInputStream(Path.of(review.path()))) {
            return new UploadFile("사업계획_오류.xlsx",writer.errorFile(in,issues,BusinessPlanUploadForm.HEADERS));
        } catch(IOException|RuntimeException e) {
            try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new ByteArrayOutputStream()) {
                var sheet=book.createSheet("오류");for(int n=0;n<issues.size();n++) sheet.createRow(n).createCell(0).setCellValue(issues.get(n).message());
                book.write(out);return new UploadFile("사업계획_오류.xlsx",out.toByteArray());
            } catch(IOException failure) {throw new IllegalStateException(failure);}
        }
    }
    @Override public UploadFile template() {
        permissions.can(permissionKey("download"));
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("사업계획");sheet.createRow(0).createCell(0).setCellValue("B.10 · 한 연도의 목표를 입력하세요. 마감 월은 유지하고 미마감 월 전체를 교체합니다.");
            var title=sheet.createRow(3);for(int c=0;c<BusinessPlanUploadForm.HEADERS.size();c++) {
                title.createCell(c).setCellValue(BusinessPlanUploadForm.HEADERS.get(c));sheet.setColumnWidth(c,24*256);
            }
            sheet.createFreezePane(0,4);book.write(out);return new UploadFile("사업계획_업로드양식.xlsx",out.toByteArray());
        } catch(IOException e) {throw new IllegalStateException(e);}
    }
    private String snapshot(Object value) {
        try {return json.writeValueAsString(value);} catch(IOException e) {throw new IllegalStateException(e);}
    }
    private static String checksum(Path path) {
        try(InputStream in=Files.newInputStream(path)) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];
            for(int n;(n=in.read(buffer))!=-1;) digest.update(buffer,0,n);return HexFormat.of().formatHex(digest.digest());
        } catch(IOException|NoSuchAlgorithmException e) {throw BusinessPlanUploadInput.invalid("file");}
    }
}
