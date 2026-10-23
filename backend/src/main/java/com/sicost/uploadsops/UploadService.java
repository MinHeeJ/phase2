package com.sicost.uploadsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.uploadsops.UploadModels.*;

@Service
public class UploadService {
    private final UploadMapper mapper;
    private final UploadStorage storage;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Map<String,ValidatedFile> tokens=new HashMap<>();
    public UploadService(UploadMapper mapper,UploadStorage storage,CurrentUser user,PermissionService permissions,
        ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper;this.storage=storage;this.user=user;this.permissions=permissions;this.clock=storage.clock();this.json=json;
        this.transaction=new TransactionTemplate(manager);
    }
    private void requireForm(String form) {if(!"B.7".equals(form))throw ApiException.notFound("업로드 양식이 없습니다.");}
    public Download template(String form) {
        requireForm(form);permissions.can("UI-15.download");
        try {byte[] bytes=ExcelTemplateWriter.write(mapper.codes());String name="거래처_업로드양식.xlsx";access("DOWNLOAD",name,Map.of("form",form));return new Download(name,bytes);}
        catch(IOException ex) {throw new ApiException(500,"SERVER_ERROR","양식을 만들 수 없습니다.",null);}
    }
    public Validation validate(String form,MultipartFile file) {
        requireForm(form);permissions.can("UI-15.upload");return inspect(storage.save(file));
    }
    public Validation recheck(String id) {
        permissions.can("UI-15.upload");return inspect(requireOriginal(id));
    }
    private Original requireOriginal(String id) {
        long value;
        try {if(!id.matches("[1-9][0-9]{0,18}"))throw new NumberFormatException();value=Long.parseLong(id);}
        catch(NumberFormatException ex) {throw ApiException.badRequest("업로드 번호를 확인하세요.","upldId");}
        Original original=mapper.original(value);if(original==null)throw ApiException.notFound("업로드가 없습니다. 파일을 다시 올려 주세요.");return original;
    }
    private XSSFWorkbook read(Original original) {
        try(InputStream in=storage.open(original)) {return new XSSFWorkbook(in);}
        catch(ApiException ex) {throw ex;}
        catch(IOException|RuntimeException ex) {throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","읽을 수 있는 xlsx 파일을 올려 주세요.");}
    }
    private Validation inspect(Original original) {
        Parsed parsed;
        try(XSSFWorkbook book=read(original)) {parsed=PartnerUploadForm.parse(book,mapper);}
        catch(IOException ex) {throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","읽을 수 있는 xlsx 파일을 올려 주세요.");}
        Set<Integer> errors=new HashSet<>(),warnings=new HashSet<>(),all=new HashSet<>();
        for(Issue issue:parsed.items()) {all.add(issue.rowNo());if("ERROR".equals(issue.severity()))errors.add(issue.rowNo());else warnings.add(issue.rowNo());}
        int total=parsed.rows().size();Validation counts=new Validation(original.fileName(),total,total-all.size(),errors.size(),warnings.size(),parsed.items(),null,null);
        if(!errors.isEmpty()) {
            Long id=transaction.execute(status->{long key=mapper.insertLedger(new Ledger(original,counts,"REJECTED",user.userId()));for(Issue issue:parsed.items())mapper.insertIssue(new IssueWrite(key,issue));return key;});
            return new Validation(original.fileName(),total,counts.okRows(),counts.errorRows(),counts.warningRows(),parsed.items(),Long.toString(id),null);
        }
        String token=UUID.randomUUID().toString();
        Validation result=new Validation(original.fileName(),total,counts.okRows(),0,counts.warningRows(),parsed.items(),null,token);
        synchronized(tokens) {tokens.entrySet().removeIf(e->expired(e.getValue()));tokens.put(token,new ValidatedFile(original,parsed,result,clock.instant()));}
        return result;
    }
    private boolean expired(ValidatedFile file) {return !clock.instant().isBefore(file.issuedAt.plus(Duration.ofMinutes(30)));}
    private ApiException expiry() {return new ApiException(410,"VALIDATION_EXPIRED","검사 후 30분이 지났습니다. 다시 올려 주세요.",null);}
    public Applied apply(String token) {
        permissions.can("UI-15.upload");
        if(!token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw ApiException.badRequest("검사 토큰을 확인하세요.","token");
        ValidatedFile file;synchronized(tokens) {file=tokens.get(token.toLowerCase(Locale.ROOT));}
        if(file==null)throw expiry();
        synchronized(file) {
            if(expired(file))throw expiry();if(file.completed!=null)return file.completed;
            Applied result;
            try {result=transaction.execute(status->applyInside(file));}
            catch(DataIntegrityViolationException ex) {
                Throwable cause=ex;while(cause!=null) {if(cause instanceof java.sql.SQLException sql&&"23505".equals(sql.getSQLState()))throw stale();cause=cause.getCause();}
                if(ex instanceof org.springframework.dao.DuplicateKeyException)throw stale();throw ex;
            }
            file.completed=result;return result;
        }
    }
    private Applied applyInside(ValidatedFile file) {
        for(PartnerRow row:file.parsed.rows())if(mapper.existingPartner(row.businessNo())!=null)throw stale();
        long id=mapper.insertLedger(new Ledger(file.original,file.result,"APPLIED",user.userId()));
        for(PartnerRow row:file.parsed.rows()) {
            long partner=mapper.reservePartnerId();String no="C-"+String.format(Locale.ROOT,"%03d",partner);
            mapper.insertPartner(new PartnerWrite(partner,no,row,id,user.userId()));
            Map<String,Object> safe=new LinkedHashMap<>();safe.put("ptnr_no",no);safe.put("ptnr_nm",row.name());safe.put("ptnr_se_cd",row.kind());safe.put("biz_reg_no",row.businessNo());safe.put("use_yn","Y");
            mapper.insertHistory(new HistoryWrite(partner,id,serialize(safe),user.userId()));
        }
        for(Issue issue:file.parsed.items())mapper.insertIssue(new IssueWrite(id,issue));
        mapper.insertAccess(new AccessWrite("UPLOAD",file.original.fileName(),serialize(Map.of("upldId",Long.toString(id),"appliedRows",file.parsed.rows().size(),"actorName",user.name())),user.userId()));
        return new Applied(Long.toString(id),file.parsed.rows().size());
    }
    private ApiException stale() {return ApiException.conflict("STALE_VALIDATION","다시 검사해 주세요.",null);}
    private String serialize(Object value) {
        try {return json.writeValueAsString(value);}catch(JsonProcessingException ex) {throw new IllegalStateException("JSON serialization failed",ex);}
    }
    private void access(String type,String name,Map<String,Object> detail) {
        Map<String,Object> values=new LinkedHashMap<>(detail);values.put("actorName",user.name());
        transaction.executeWithoutResult(status->mapper.insertAccess(new AccessWrite(type,name,serialize(values),user.userId())));
    }
    public Download errorFile(String id) {
        permissions.can("UI-15.download");Original original=requireOriginal(id);List<Issue> issues=mapper.issues(Long.parseLong(id));
        try(XSSFWorkbook source=read(original)) {
            byte[] bytes=ExcelErrorFileWriter.write(source,issues);String name=original.fileName().replaceFirst("(?i)\\.xlsx$","")+"_오류.xlsx";
            access("DOWNLOAD",name,Map.of("upldId",id));return new Download(name,bytes);
        } catch(IOException ex) {throw new ApiException(500,"SERVER_ERROR","오류 파일을 만들 수 없습니다.",null);}
    }
}
