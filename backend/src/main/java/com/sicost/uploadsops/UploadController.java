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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private java.util.List<UploadReviewAdapter> adapters=java.util.List.of();
    private UploadReviewAdapter adapter(long id) {
        return adapters.stream().filter(a->a.supports(id)).findFirst().orElse(null);
    }
    public UploadController(UploadService service,PermissionService permissions){this.service=service;this.permissions=permissions;}
    private ResponseEntity<byte[]> file(UploadFile file){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString()).body(file.bytes());}
    @GetMapping("/templates/{form}") public ResponseEntity<byte[]> getUploadTemplate(@PathVariable String form){
        var extension=adapters.stream().filter(a->a.supportsForm(form)).findFirst().orElse(null);
        if(extension!=null){permissions.can(extension.permissionKey("download"));return file(extension.template());}
        permissions.can("B.7".equals(form)?"UI-15.download":"UI-02.download");return file(service.template(form));}
    @PostMapping(value="/{form}/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<UploadValidationResult> createUploadValidation(@PathVariable String form,@RequestPart MultipartFile file){permissions.can("UI-15.upload");return ApiResponse.ok(service.validate(form,file));}
    @PostMapping("/{upldId}/recheck") public ApiResponse<UploadValidationResult> saveUploadRecheck(@PathVariable long upldId){permissions.can("UI-15.upload");return ApiResponse.ok(service.recheck(upldId));}
    @PostMapping("/validated/{token}/apply") public ApiResponse<UploadApplyResult> saveValidatedUploadApply(@PathVariable String token){permissions.can("UI-15.upload");return ApiResponse.ok(service.apply(token));}
    @GetMapping("/{upldId}/error-file") public ResponseEntity<byte[]> getUploadErrorFile(@PathVariable long upldId){permissions.can("UI-15.download");return file(service.errorFile(upldId));}
    @PostMapping(value="/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PersonnelUploadModels.Result> createPersonnelUploadValidation(@RequestParam("form_type") String form,@RequestParam(required=false) String month,@RequestPart MultipartFile file){permissions.can("UI-02.upload");return ApiResponse.ok(service.validatePersonnel(form,month,file));}
    @GetMapping("/{uploadId}/issues") public ApiResponse<PageResult<PersonnelUploadModels.Issue>> listUploadIssues(@PathVariable long uploadId,@RequestParam(required=false) String severity,@RequestParam(required=false) String column,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="row,asc") String sort){var a=adapter(uploadId);permissions.can(a==null?"UI-02.upload":a.permissionKey("upload"));return ApiResponse.ok(a==null?service.personnel().issues(uploadId,severity,column,new DeveloperPaging(page,size,sort)):a.issues(uploadId,severity,column,new DeveloperPaging(page,size,sort)));}
    @GetMapping("/{uploadId}/rows") public ApiResponse<?> listUploadRows(@PathVariable long uploadId,@RequestParam(required=false) String severity,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="row,asc") String sort){var a=adapter(uploadId);permissions.can(a==null?"UI-02.upload":a.permissionKey("upload"));return ApiResponse.ok(a==null?service.personnel().rows(uploadId,severity,new DeveloperPaging(page,size,sort)):a.rows(uploadId,severity,new DeveloperPaging(page,size,sort)));}
    @PostMapping("/{uploadId}/revalidate") public ApiResponse<PersonnelUploadModels.Result> createUploadRevalidation(@PathVariable long uploadId){var a=adapter(uploadId);permissions.can(a==null?"UI-02.upload":a.permissionKey("upload"));return ApiResponse.ok(a==null?service.personnel().revalidate(uploadId):a.revalidate(uploadId));}
    @PostMapping("/{uploadId}/apply") public ApiResponse<?> saveUploadApplication(@PathVariable long uploadId){var a=adapter(uploadId);permissions.can(a==null?"UI-02.upload":a.permissionKey("upload"));return ApiResponse.ok(a==null?service.personnel().apply(uploadId):a.apply(uploadId));}
    @GetMapping("/{uploadId}/errors") public ResponseEntity<byte[]> getPersonnelUploadErrorFile(@PathVariable long uploadId){var a=adapter(uploadId);permissions.can(a==null?"UI-02.upload":a.permissionKey("download"));return file(a==null?service.personnel().errors(uploadId):a.errors(uploadId));}
}
