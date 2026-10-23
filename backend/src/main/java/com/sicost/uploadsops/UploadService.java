package com.sicost.uploadsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.uploadsops.UploadModels.*;

@Service
public class UploadService {
    public static final long MAX_BYTES=10L*1024*1024;
    private final UploadMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final UploadStorage storage;
    private final Map<String,Validation> validations=Collections.synchronizedMap(new HashMap<>());
    public UploadService(UploadMapper mapper,CurrentUser user,PermissionService permissions,Clock clock,PlatformTransactionManager manager,UploadStorage storage) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.transaction=new TransactionTemplate(manager);this.storage=storage;
    }
    private void form(String form){if(!"B.7".equals(form))throw ApiException.notFound("양식이 없습니다.");}
    public Download template(String form) {
        form(form);permissions.can("UI-15.download");
        return transaction.execute(s->{byte[] bytes=ExcelTemplateWriter.write(mapper.codes());String name="거래처_업로드양식.xlsx";access("DOWNLOAD",name,null);return new Download(name,bytes);});
    }
    public Result validate(String form,MultipartFile file) {
        form(form);permissions.can("UI-15.upload");
        if(file.getSize()>MAX_BYTES)throw new ApiException(413,"INVALID_INPUT","파일 크기는 10MB 이하여야 합니다.","file");
        String name=file.getOriginalFilename();
        if(name==null||!name.toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw unsupported();
        name=name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
        if(name.isBlank()||name.length()>190)throw ApiException.badRequest("파일명을 확인해 주세요.","file");
        if(file.isEmpty())throw UploadForm.fileError("EMPTY_FILE","데이터가 없습니다.");
        Path path=storage.save(file,clock);return inspect(name,path);
    }
    public Result recheck(String id) {
        permissions.can("UI-15.upload");Original original=original(id);return inspect(original.fileName(),storage.existing(original.path()));
    }
    private Result inspect(String name,Path path) {
        Parsed parsed;
        try(var book=openWorkbook(path)) {parsed=UploadForm.parse(book,mapper);}
        catch(IOException ex){throw unsupported();}
        int errors=(int)parsed.issues().stream().filter(i->i.severity().equals("ERROR")).map(Issue::rowNo).distinct().count();
        int warnings=(int)parsed.issues().stream().filter(i->i.severity().equals("WARN")).map(Issue::rowNo).distinct().count();
        Result result=new Result(name,parsed.rows().size(),parsed.rows().size()-errors,errors,warnings,parsed.issues(),null,null);
        if(errors>0) {
            Long id=transaction.execute(s->{Long key=mapper.insertLedger(new Ledger(name,path.toString(),result,"REJECTED",user.userId()));saveIssues(key,result.items());return key;});
            return new Result(name,result.totalRows(),result.okRows(),errors,warnings,result.items(),id.toString(),null);
        }
        String token=UUID.randomUUID().toString();
        Result accepted=new Result(name,result.totalRows(),result.okRows(),0,warnings,result.items(),null,token);
        synchronized(validations){validations.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue().expires));validations.put(token,new Validation(path,accepted,parsed.rows(),clock.instant().plus(Duration.ofMinutes(30))));}
        return accepted;
    }
    private XSSFWorkbook openWorkbook(Path path) {
        try(var input=Files.newInputStream(path)) { return new XSSFWorkbook(input); }
        catch(Exception ex){throw unsupported();}
    }
    public Applied apply(String token) {
        permissions.can("UI-15.upload");
        if(!token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw ApiException.badRequest("검사 토큰을 확인해 주세요.","token");
        Validation validation=validations.get(token.toLowerCase(Locale.ROOT));if(validation==null)throw expired();
        synchronized(validation) {
            if(validation.consumed)throw stale();
            if(!clock.instant().isBefore(validation.expires))throw expired();
            try {
                Applied applied=transaction.execute(s->{
                    for(Partner row:validation.rows)if(mapper.existing(row.bizNo())!=null)throw stale();
                    Long id=mapper.insertLedger(new Ledger(validation.result.fileName(),validation.path.toString(),validation.result,"APPLIED",user.userId()));
                    List<History> history=new ArrayList<>();
                    for(Partner row:validation.rows) {
                        Long partnerId=mapper.nextPartnerId();String no="C-"+String.format(Locale.ROOT,"%03d",partnerId);
                        mapper.insertPartner(new PartnerWrite(partnerId,no,id,row,user.userId()));
                        history.add(new History(partnerId,id,row.name(),user.userId()));
                    }
                    saveIssues(id,validation.result.items());
                    history.forEach(mapper::history);
                    return complete(id,validation);
                });
                validation.consumed=true;return applied;
            } catch(DuplicateKeyException ex){throw stale();}
        }
    }
    private Applied complete(Long id,Validation validation) {
        access("UPLOAD",validation.result.fileName(),id);return new Applied(id.toString(),validation.rows.size());
    }
    public Download errorFile(String id) {
        permissions.can("UI-15.download");Original original=original(id);Path path=storage.existing(original.path());
        String name=original.fileName().replaceFirst("(?i)\\.xlsx$","")+"_오류.xlsx";
        return transaction.execute(s->{byte[] bytes=ExcelErrorFileWriter.write(path,mapper.issues(identifier(id)).stream().map(StoredIssue::issue).toList());access("DOWNLOAD",name,identifier(id));return new Download(name,bytes);});
    }
    private Original original(String id){Original row=mapper.original(identifier(id));if(row==null)throw ApiException.notFound("업로드가 없습니다.");return row;}
    private Long identifier(String id){try{if(!id.matches("[1-9][0-9]{0,18}"))throw new NumberFormatException();return Long.valueOf(id);}catch(NumberFormatException ex){throw ApiException.badRequest("업로드 번호를 확인해 주세요.","upldId");}}
    private void saveIssues(Long id,List<Issue> issues){for(Issue issue:issues)mapper.insertIssue(new IssueWrite(id,issue));}
    private void access(String type,String name,Long id){mapper.access(new Access(type,name,id==null?"{\"form\":\"B.7\"}":"{\"upldId\":\""+id+"\"}",user.userId()));}
    private ApiException expired(){return new ApiException(410,"VALIDATION_EXPIRED","검사 후 30분이 지났습니다. 다시 올려 주세요.","token");}
    private ApiException stale(){return ApiException.conflict("STALE_VALIDATION","다시 검사해 주세요.","token");}
    private ApiException unsupported(){return UploadForm.fileError("UNSUPPORTED_FILE_TYPE","xlsx 파일만 올릴 수 있습니다.");}
}
