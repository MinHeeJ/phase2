package com.sicost.uploadsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.uploadsops.UploadModels.*;

@Service
public class UploadService {
    private final UploadMapper mapper;
    private final CurrentUser user;
    private final Clock clock;
    private final UploadWorkbookWriter writer;
    private final PartnerUploadForm form;
    private final TransactionTemplate transaction;
    private final Map<String,Validation> validations=new HashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required=false) private PersonnelUploadService personnel;
    @Value("${sicost.upload-directory:${java.io.tmpdir}/sicost/uploads}") private String directory;
    public UploadService(UploadMapper mapper,CurrentUser user,Clock clock,UploadWorkbookWriter writer,PartnerUploadForm form,PlatformTransactionManager manager) {
        this.mapper=mapper;this.user=user;this.clock=clock;this.writer=writer;this.form=form;this.transaction=new TransactionTemplate(manager);
    }
    private static final class Validation {
        final UploadContext context;final Instant expires;UploadApplyResult completed;
        Validation(UploadContext context,Instant expires){this.context=context;this.expires=expires;}
    }
    static void requireForm(String form){if(!"B.7".equals(form))throw ApiException.notFound("업로드 양식이 없습니다.");}
    static String filename(String original) {
        if(original==null||original.isBlank())throw ApiException.badRequest("파일을 선택해 주세요.","file");
        String name=original.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        if(name.length()>255)throw ApiException.badRequest("파일명은 255자 이하로 입력해 주세요.","file");
        name=name.replaceAll("[\\p{Cntrl}<>:\"|?*]","_");
        if(name.isBlank())throw ApiException.badRequest("파일명을 확인해 주세요.","file");return name;
    }
    public UploadValidationResult validate(String requestedForm,MultipartFile file) {
        requireForm(requestedForm);String name=filename(file.getOriginalFilename());
        return inspect(name,store(file,name));
    }
    public PersonnelUploadService personnel(){return Objects.requireNonNull(personnel);}
    public PersonnelUploadModels.Result validatePersonnel(String requestedForm,String month,MultipartFile file){
        PersonnelUploadService.formType(requestedForm);
        if("B.1".equals(requestedForm))com.sicost.common.validation.CalendarValues.month(month,"month");
        else if(month!=null)throw ApiException.badRequest("개발자 업로드에는 연월을 입력하지 않습니다.","month");
        String name=filename(file.getOriginalFilename());return personnel().validate(requestedForm,month,name,store(file,name));
    }
    private Path store(MultipartFile file,String name) {
        if(file.getSize()>10L*1024*1024)throw new ApiException(413,"INVALID_INPUT","파일은 10MB 이하만 올릴 수 있습니다.","file");
        if(!name.toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","xlsx 파일만 올릴 수 있습니다.");
        if(file.isEmpty())throw PartnerUploadForm.fileError("EMPTY_FILE","검사할 데이터가 없습니다.");
        Path root=Path.of(directory).toAbsolutePath().normalize();
        Path target=root.resolve(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).format(DateTimeFormatter.ofPattern("yyyy/MM"))).resolve(UUID.randomUUID()+".xlsx");
        try {Files.createDirectories(target.getParent());try(InputStream in=file.getInputStream()){Files.copy(in,target);}return target;}
        catch(ApiException ex){throw ex;}
        catch(IOException ex){throw new ApiException(500,"SERVER_ERROR","파일을 처리할 수 없습니다. 다시 올려 주세요.","file");}
    }
    private UploadValidationResult inspect(String name,Path path) {
        ParsedUpload parsed;
        try(InputStream in=Files.newInputStream(path)){parsed=form.parse(in);}
        catch(ApiException ex){throw ex;}
        catch(IOException ex){throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","올바른 xlsx 파일만 올릴 수 있습니다.");}
        UploadContext context=new UploadContext(name,path.toString(),parsed);
        int errors=(int)parsed.items().stream().filter(i->i.severity().equals("ERROR")).map(UploadIssue::rowNo).distinct().count();
        int warnings=(int)parsed.items().stream().filter(i->i.severity().equals("WARN")).map(UploadIssue::rowNo).distinct().count();
        UploadValidationResult result=new UploadValidationResult(name,null,null,parsed.rows().size(),parsed.rows().size()-errors,errors,warnings,parsed.items());
        if(errors>0) {
            Long id=transaction.execute(status->{long upload=mapper.insertUpload(new UploadWrite(context,result,"REJECTED",user.userId()));saveIssues(upload,parsed.items());return upload;});
            return new UploadValidationResult(name,Long.toString(Objects.requireNonNull(id)),null,result.totalRows(),result.okRows(),errors,warnings,result.items());
        }
        String token=UUID.randomUUID().toString();
        synchronized(validations){validations.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue().expires));validations.put(token,new Validation(context,clock.instant().plus(Duration.ofMinutes(30))));}
        return new UploadValidationResult(name,null,token,result.totalRows(),result.okRows(),0,warnings,result.items());
    }
    private UploadSource source(long id) {
        UploadSource source=mapper.source(id);
        if(source==null||source.path()==null||!Files.isRegularFile(Path.of(source.path())))throw ApiException.notFound("원본 파일이 없습니다. 다시 올려 주세요.");return source;
    }
    public UploadValidationResult recheck(long id) {UploadSource source=source(id);return inspect(source.fileName(),Path.of(source.path()));}
    private static ApiException expired(){return new ApiException(410,"VALIDATION_EXPIRED","검사 후 30분이 지났습니다. 다시 올려 주세요.",null);}
    private static ApiException stale(){return ApiException.conflict("STALE_VALIDATION","다시 검사해 주세요.",null);}
    public UploadApplyResult apply(String token) {
        if(!token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw ApiException.badRequest("검사 토큰을 확인해 주세요.","token");
        Validation validation;synchronized(validations){validation=validations.get(token);}
        if(validation==null)throw expired();
        synchronized(validation) {
            if(!clock.instant().isBefore(validation.expires))throw expired();
            if(validation.completed!=null)return validation.completed;
            try {
                UploadApplyResult result=transaction.execute(status->{
                    UploadContext context=validation.context;ParsedUpload parsed=context.parsed();
                    for(var row:parsed.rows())if(mapper.duplicateName(row.biz_reg_no())!=null)throw stale();
                    int warningRows=(int)parsed.items().stream().map(UploadIssue::rowNo).distinct().count();
                    UploadValidationResult counts=new UploadValidationResult(context.fileName(),null,null,parsed.rows().size(),parsed.rows().size(),0,warningRows,parsed.items());
                    long id=mapper.insertUpload(new UploadWrite(context,counts,"APPLIED",user.userId()));
                    for(var row:parsed.rows()) {
                        long partnerId=mapper.nextPartnerId();String no="C-"+String.format(Locale.ROOT,"%03d",partnerId);
                        mapper.insertPartner(new PartnerImport(partnerId,no,row,id,user.userId()));
                        mapper.appendHistory(new ImportHistory(partnerId,id,user.userId()));
                    }
                    saveIssues(id,parsed.items());access("UPLOAD",context.fileName(),Map.of("upldId",Long.toString(id),"appliedRows",parsed.rows().size()));
                    return new UploadApplyResult(Long.toString(id),parsed.rows().size());
                });
                validation.completed=Objects.requireNonNull(result);return result;
            } catch(DuplicateKeyException ex){throw stale();}
        }
    }
    private void saveIssues(long id,List<UploadIssue> issues){for(UploadIssue issue:issues){String raw=issue.rawValue();mapper.insertIssue(new IssueWrite(id,issue,raw==null?null:raw.substring(0,Math.min(raw.length(),500))));}}
    private void access(String type,String name,Map<String,Object> detail) {
        Map<String,Object> values=new LinkedHashMap<>(detail);values.put("fileName",name);
        try {String json=new ObjectMapper().writeValueAsString(values);mapper.access(new UploadAccess(type,name.substring(0,Math.min(200,name.length())),json,user.userId()));}
        catch(JsonProcessingException ex){throw new IllegalStateException("접근 기록 생성 실패",ex);}
    }
    public UploadFile template(String requestedForm) {
        if("B.8".equals(requestedForm)||"B.1".equals(requestedForm))return personnel().template(requestedForm);
        requireForm(requestedForm);
        try {byte[] bytes=writer.template(mapper.codes());String name="거래처_업로드양식.xlsx";access("DOWNLOAD",name,Map.of("form","B.7"));return new UploadFile(name,bytes);}
        catch(IOException ex){throw new IllegalStateException("양식 생성 실패",ex);}
    }
    public UploadFile errorFile(long id) {
        UploadSource source=source(id);
        try(InputStream input=Files.newInputStream(Path.of(source.path()))) {
            byte[] bytes=writer.errorFile(input,mapper.issues(id));String name=source.fileName().replaceFirst("(?i)\\.xlsx$","")+"_오류.xlsx";
            access("DOWNLOAD",name,Map.of("upldId",Long.toString(id)));return new UploadFile(name,bytes);
        }catch(IOException ex){throw ApiException.notFound("원본 파일을 읽을 수 없습니다. 다시 올려 주세요.");}
    }
}
