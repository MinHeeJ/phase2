package com.sicost.uploadsops;

import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.uploadsops.UploadModels.*;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadService service;
    private final PermissionService permissions;
    public UploadController(UploadService service,PermissionService permissions){this.service=service;this.permissions=permissions;}
    @PostMapping(value="/dispatch-attendance/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PersonnelUploadModels.Result> validateDispatchAttendanceUpload(@RequestParam String month,@RequestParam long projectId,@RequestPart MultipartFile file){permissions.can("UI-13.upload");return ApiResponse.ok(service.validateDispatchAttendance(month,projectId,file));}
    @PostMapping("/dispatch-attendance/apply")
    public ApiResponse<Object> applyDispatchAttendanceUpload(@RequestBody com.fasterxml.jackson.databind.JsonNode body){
        permissions.can("UI-13.upload");
        if(body==null||!body.path("upload_id").isIntegralNumber()||!body.path("upload_id").canConvertToLong()||body.path("upload_id").longValue()<1)throw com.sicost.common.billing.BillingRules.invalid("upload_id");
        if(!body.path("confirm").isBoolean()||!body.path("confirm").booleanValue())throw com.sicost.common.billing.BillingRules.invalid("confirm");
        return ApiResponse.ok(service.applyDispatchAttendance(body.path("upload_id").longValue()));
    }
    private ResponseEntity<byte[]> file(UploadFile file){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString()).body(file.bytes());}
    @GetMapping("/templates/{form}") public ResponseEntity<byte[]> getUploadTemplate(@PathVariable String form){permissions.can("B.7".equals(form)?"UI-15.download":"UI-02.download");return file(service.template(form));}
    @PostMapping(value="/{form}/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<UploadValidationResult> createUploadValidation(@PathVariable String form,@RequestPart MultipartFile file){permissions.can("UI-15.upload");return ApiResponse.ok(service.validate(form,file));}
    @PostMapping("/{upldId}/recheck") public ApiResponse<UploadValidationResult> saveUploadRecheck(@PathVariable long upldId){permissions.can("UI-15.upload");return ApiResponse.ok(service.recheck(upldId));}
    @PostMapping("/validated/{token}/apply") public ApiResponse<UploadApplyResult> saveValidatedUploadApply(@PathVariable String token){permissions.can("UI-15.upload");return ApiResponse.ok(service.apply(token));}
    @GetMapping("/{upldId}/error-file") public ResponseEntity<byte[]> getUploadErrorFile(@PathVariable long upldId){permissions.can("UI-15.download");return file(service.errorFile(upldId));}
    @PostMapping(value="/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PersonnelUploadModels.Result> createPersonnelUploadValidation(@RequestParam("form_type") String form,@RequestParam(required=false) String month,@RequestPart MultipartFile file){permissions.can("UI-02.upload");return ApiResponse.ok(service.validatePersonnel(form,month,file));}
    @GetMapping("/{uploadId}/issues") public ApiResponse<PageResult<PersonnelUploadModels.Issue>> listUploadIssues(@PathVariable long uploadId,@RequestParam(required=false) String severity,@RequestParam(required=false) String column,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="row,asc") String sort){permissions.can("UI-02.upload");var delegate=service.delegate(uploadId);return ApiResponse.ok(delegate==null?service.personnel().issues(uploadId,severity,column,new DeveloperPaging(page,size,sort)):delegate.issues(uploadId,severity,column,new DeveloperPaging(page,size,sort)));}
    @GetMapping("/{uploadId}/rows") public ApiResponse<PageResult<?>> listUploadRows(@PathVariable long uploadId,@RequestParam(required=false) String severity,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="row,asc") String sort){permissions.can("UI-02.upload");var delegate=service.delegate(uploadId);return ApiResponse.ok(delegate==null?service.personnel().rows(uploadId,severity,new DeveloperPaging(page,size,sort)):delegate.rows(uploadId,severity,new DeveloperPaging(page,size,sort)));}
    @PostMapping("/{uploadId}/revalidate") public ApiResponse<PersonnelUploadModels.Result> createUploadRevalidation(@PathVariable long uploadId){permissions.can("UI-02.upload");var delegate=service.delegate(uploadId);return ApiResponse.ok(delegate==null?service.personnel().revalidate(uploadId):delegate.revalidate(uploadId));}
    @PostMapping("/{uploadId}/apply") public ApiResponse<PersonnelUploadModels.Application> saveUploadApplication(@PathVariable long uploadId){permissions.can("UI-02.upload");return ApiResponse.ok(service.personnel().apply(uploadId));}
    @GetMapping("/{uploadId}/errors") public ResponseEntity<byte[]> getPersonnelUploadErrorFile(@PathVariable long uploadId){permissions.can("UI-02.upload");var delegate=service.delegate(uploadId);return file(delegate==null?service.personnel().errors(uploadId):delegate.errors(uploadId));}
}
