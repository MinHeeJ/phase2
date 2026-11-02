package com.sicost.priorprofit;

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
import static com.sicost.priorprofit.PriorProfitUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

@Service
@Transactional
public class PriorProfitUploadService implements UploadReviewAdapter {
    private final PriorProfitUploadMapper mapper;
    private final PriorProfitUploadForm form;
    private final UploadService uploads;
    private final UploadWorkbookWriter writer;
    private final CurrentUser user;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private UploadMapper common;
    @org.springframework.beans.factory.annotation.Autowired private PermissionService permissions;
    @Value("${sicost.upload-directory:${java.io.tmpdir}/sicost/uploads}") private String directory;
    public PriorProfitUploadService(PriorProfitUploadMapper mapper,PriorProfitUploadForm form,
            UploadService uploads,UploadWorkbookWriter writer,CurrentUser user,ObjectMapper json) {
        this.mapper=mapper;this.form=form;this.uploads=uploads;this.writer=writer;this.user=user;this.json=json;
    }
    @Override public String permissionKey(String action) {return "UI-08."+("download".equals(action)?"download":"read");}
    public Summary validate(MultipartFile file) {
        permissions.can(permissionKey("upload"));
        if(file==null || file.isEmpty() || file.getSize()>10L*1024*1024 || file.getOriginalFilename()==null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw PriorProfitUploadInput.invalid("file");
        String name=UploadService.filename(file.getOriginalFilename());
        return inspect(name,uploads.store(file,name));
    }
    private Parsed check(Path path) {
        Parsed parsed=form.parse(path);
        if(!mapper.hasClose()) return parsed;
        var issues=new ArrayList<>(parsed.issues());
        PriorProfitUploadForm.error(issues,1,"연월","2025년 CLOSE가 있어 연도 전체를 이관할 수 없습니다.");
        return new Parsed(parsed.rows(),parsed.details(),List.copyOf(issues));
    }
    private Summary inspect(String name,Path path) {
        long id=mapper.nextUploadId();Parsed parsed=check(path);
        Review review=new Review(id,name,path.toString(),checksum(path),parsed);
        try {Files.createDirectories(metadata(id).getParent());Files.write(metadata(id),json.writeValueAsBytes(review),StandardOpenOption.CREATE_NEW);}
        catch(IOException e) {throw new IllegalStateException("검사 결과를 보관할 수 없습니다.",e);}
        Summary summary=summary(review,0,false);
        if(summary.error_count()>0) {mapper.saveReview(new ReviewWrite(review,summary,"REJECTED",user.userId()));saveIssues(review);}
        return summary;
    }
    private Path metadata(long id) {
        if(id<1) throw PriorProfitUploadInput.invalid("upload_id");
        return Path.of(directory).toAbsolutePath().normalize().resolve("prior-profit-"+id+".json");
    }
    private Path retired(long id) {return metadata(id).resolveSibling("prior-profit-"+id+".retired");}
    @Override public boolean supports(long id) {return id>0 && Files.isRegularFile(metadata(id));}
    @Override public boolean supportsForm(String form) {return "B.11".equals(form);}
    private Review require(long id) {
        if(!supports(id)) throw ApiException.notFound("과거 손익 검사 결과가 없습니다. 다시 올려 주세요.");
        try {var review=json.readValue(Files.readAllBytes(metadata(id)),Review.class);
            if(review.id()!=id) throw PriorProfitUploadInput.invalid("upload_id");return review;
        } catch(IOException e) {throw ApiException.notFound("과거 손익 검사 결과를 읽을 수 없습니다.");}
    }
    private Summary summary(Review review,int applied,boolean completed) {
        int count=(int)review.parsed().issues().stream().filter(i->"ERROR".equals(i.severity())).map(Issue::row).distinct().count();
        var items=review.parsed().issues().stream().map(i->new Item(i.column(),i.message(),i.row(),i.severity())).toList();
        return new Summary(Long.toString(review.id()),count,items,review.parsed().rows().size(),
            count==0 && !review.parsed().rows().isEmpty() && !completed && !Files.exists(retired(review.id())),
            applied,review.parsed().rows(),review.parsed().details(),"2025년 IMPORT 손익과 판관비 상세 전체를 새 파일로 교체합니다. CLOSE가 하나라도 있으면 전체 미반영합니다.");
    }
    public Summary apply(long id,boolean confirm) {
        permissions.can(permissionKey("upload"));Review review=require(id);
        if(confirm) mapper.lockScope();
        boolean completed="APPLIED".equals(mapper.uploadStatus(id));
        if(completed) return summary(review,review.parsed().rows().size(),true);
        if(mapper.hasClose()) throw ApiException.conflict("CLOSED_MONTH","2025년 CLOSE가 있어 연도 전체를 이관할 수 없습니다.","year");
        if(!summary(review,0,false).can_apply()) throw PriorProfitUploadInput.invalid("upload_id");
        if(!checksum(Path.of(review.path())).equals(review.checksum())) throw PriorProfitUploadInput.invalid("file");
        Parsed current=check(Path.of(review.path()));
        if(!current.equals(review.parsed())) throw PriorProfitUploadInput.invalid("upload_id");
        if(!confirm) return summary(review,0,false);
        var before=mapper.snapshots();var oldDetails=mapper.details();
        mapper.saveReview(new ReviewWrite(review,summary(review,0,false),"APPLIED",user.userId()));saveIssues(review);
        mapper.deleteImports();
        for(Snapshot row:before) audit("fin.mth_pl",row.month(),"DELETE",row,null);
        for(Detail row:oldDetails) audit("fin.mth_pl_dtl",row.month()+":"+row.code(),"DELETE",row,null);
        for(Preview preview:current.rows()) {
            Snapshot row=preview.profit();mapper.insertSnapshot(new SnapshotWrite(row,id,user.userId()));
            audit("fin.mth_pl",row.month(),"INSERT",null,row);
        }
        for(DetailPreview preview:current.details()) {
            Detail row=preview.detail();mapper.insertDetail(row);audit("fin.mth_pl_dtl",row.month()+":"+row.code(),"INSERT",null,row);
        }
        mapper.access(new UploadAccess("UPLOAD","B.11 과거 손익",snapshot(Map.of("upload_id",id,"year",2025,"applied_count",current.rows().size())),user.userId()));
        return summary(review,current.rows().size(),true);
    }
    private void audit(String table,String id,String type,Object before,Object after) {
        mapper.audit(new Audit(table,id,type,before==null?null:snapshot(before),after==null?null:snapshot(after),user.userId()));
    }
    private void saveIssues(Review review) {
        for(Issue issue:review.parsed().issues()) common.insertIssue(new IssueWrite(review.id(),
            new UploadIssue("ERROR",issue.row(),issue.column(),issue.raw_value(),issue.message(),null),issue.raw_value()));
    }
    @Override public Result result(long id) {
        permissions.can(permissionKey("upload"));Review review=require(id);Summary summary=summary(review,0,"APPLIED".equals(mapper.uploadStatus(id)));
        return new Result(id,"B.11",null,summary.row_count(),summary.error_count(),0,summary.can_apply(),0,
            summary.overwrite_notice(),"/api/uploads/"+id+"/issues",true);
    }
    @Override public Result revalidate(long id) {
        permissions.can(permissionKey("upload"));Review review=require(id);
        if("APPLIED".equals(mapper.uploadStatus(id))) throw PriorProfitUploadInput.invalid("upload_id");
        if(!checksum(Path.of(review.path())).equals(review.checksum())) throw PriorProfitUploadInput.invalid("file");
        Summary next=inspect(review.name(),Path.of(review.path()));
        try {Files.writeString(retired(id),"superseded");} catch(IOException e) {throw new IllegalStateException(e);}
        return result(Long.parseLong(next.upload_id()));
    }
    @Override public Object apply(long id) {return apply(id,true);}
    @Override public PageResult<Issue> issues(long id,String severity,String column,DeveloperPaging paging) {
        permissions.can(permissionKey("upload"));checkSeverity(severity);
        if(column!=null && !PriorProfitUploadForm.HEADERS.contains(column) && !Set.of("파일","항목","금액").contains(column)) throw PriorProfitUploadInput.invalid("column");
        return page(require(id).parsed().issues().stream().filter(i->severity==null||severity.equals(i.severity()))
            .filter(i->column==null||column.equals(i.column())).toList(),paging);
    }
    @Override public Object rows(long id,String severity,DeveloperPaging paging) {
        permissions.can(permissionKey("upload"));checkSeverity(severity);
        return page(require(id).parsed().rows().stream().filter(i->severity==null||severity.equals(i.severity())).toList(),paging);
    }
    private static void checkSeverity(String severity) {
        if(severity!=null && !Set.of("NORMAL","WARNING","ERROR").contains(severity)) throw PriorProfitUploadInput.invalid("severity");
    }
    private static <T> PageResult<T> page(List<T> input,DeveloperPaging paging) {
        if(paging.sort()!=null && !Set.of("row,asc","row,desc").contains(paging.sort())) throw PriorProfitUploadInput.invalid("sort");
        var list=new ArrayList<>(input);if("row,desc".equals(paging.sort())) Collections.reverse(list);
        int from=(int)Math.min((long)paging.page()*paging.size(),list.size()),to=Math.min(from+paging.size(),list.size());
        return paging.result(List.copyOf(list.subList(from,to)),list.size());
    }
    @Override public UploadFile errors(long id) {
        permissions.can(permissionKey("download"));Review review=require(id);
        var issues=review.parsed().issues().stream().map(i->new UploadIssue("ERROR",i.row(),i.column(),i.raw_value(),i.message(),null)).toList();
        try(InputStream original=Files.newInputStream(Path.of(review.path()));
                var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(original);
                InputStream in=Files.newInputStream(Path.of(review.path()))) {
            if(book.isMacroEnabled() || !book.getExternalLinksTable().isEmpty()) throw new IOException("안전한 오류 목록만 제공합니다.");
            return new UploadFile("과거손익_오류.xlsx",writer.errorFile(in,issues,PriorProfitUploadForm.HEADERS));
        } catch(IOException|RuntimeException e) {
            try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new ByteArrayOutputStream()) {
                var sheet=book.createSheet("오류");for(int n=0;n<issues.size();n++) sheet.createRow(n).createCell(0).setCellValue(issues.get(n).message());
                book.write(out);return new UploadFile("과거손익_오류.xlsx",out.toByteArray());
            } catch(IOException failure) {throw new IllegalStateException(failure);}
        }
    }
    @Override public UploadFile template() {
        permissions.can(permissionKey("download"));
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("월별 손익");sheet.createRow(0).createCell(0).setCellValue("B.11 · 2025년 1~12월 손익을 입력하세요. 수식·CLOSE 덮어쓰기는 불가합니다. 배분 기준 매출은 선택입니다.");
            heading(sheet,PriorProfitUploadForm.HEADERS);
            var detail=book.createSheet("판관비 상세");detail.createRow(0).createCell(0).setCellValue("선택 상세 · 판관비 코드/명칭 또는 등록된 엑셀 표기를 입력하세요. 상세를 입력하면 월 합계가 판관비 입력 항목과 같아야 합니다. 상세가 없으면 이 시트를 제거하세요.");
            heading(detail,PriorProfitUploadForm.DETAIL_HEADERS);
            book.write(out);return new UploadFile("과거손익_업로드양식.xlsx",out.toByteArray());
        } catch(IOException e) {throw new IllegalStateException(e);}
    }
    private static void heading(org.apache.poi.ss.usermodel.Sheet sheet,List<String> headers) {
        var title=sheet.createRow(3);for(int c=0;c<headers.size();c++) {title.createCell(c).setCellValue(headers.get(c));sheet.setColumnWidth(c,24*256);}sheet.createFreezePane(0,4);
    }
    private String snapshot(Object value) {
        try {return json.writeValueAsString(value);} catch(IOException e) {throw new IllegalStateException(e);}
    }
    private static String checksum(Path path) {
        try(InputStream in=Files.newInputStream(path)) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];
            for(int n;(n=in.read(buffer))!=-1;) digest.update(buffer,0,n);return HexFormat.of().formatHex(digest.digest());
        } catch(IOException|NoSuchAlgorithmException e) {throw PriorProfitUploadInput.invalid("file");}
    }
}
