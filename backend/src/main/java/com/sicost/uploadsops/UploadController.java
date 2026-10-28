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
    private java.util.List<UploadReviewProvider> reviewProviders = java.util.List.of();
    private UploadReviewProvider provider(long id) {
        return reviewProviders.stream().filter(p -> p.owns(id)).findFirst().orElse(null);
    }
    public UploadController(UploadService service,PermissionService permissions){this.service=service;this.permissions=permissions;}
    private ResponseEntity<byte[]> file(UploadFile file){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString()).body(file.bytes());}
    @GetMapping("/templates/{form}") public ResponseEntity<byte[]> getUploadTemplate(@PathVariable String form){permissions.can("B.3".equals(form)?"UI-13.download":"B.7".equals(form)?"UI-15.download":"UI-02.download");var provider=reviewProviders.stream().filter(p->p.supports(form)).findFirst();return file(provider.isPresent()?provider.get().template():service.template(form));}
    @PostMapping(value="/{form}/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<UploadValidationResult> createUploadValidation(@PathVariable String form,@RequestPart MultipartFile file){permissions.can("UI-15.upload");return ApiResponse.ok(service.validate(form,file));}
    @PostMapping("/{upldId}/recheck") public ApiResponse<UploadValidationResult> saveUploadRecheck(@PathVariable long upldId){permissions.can("UI-15.upload");return ApiResponse.ok(service.recheck(upldId));}
    @PostMapping("/validated/{token}/apply") public ApiResponse<UploadApplyResult> saveValidatedUploadApply(@PathVariable String token){permissions.can("UI-15.upload");return ApiResponse.ok(service.apply(token));}
    @GetMapping("/{upldId}/error-file") public ResponseEntity<byte[]> getUploadErrorFile(@PathVariable long upldId){permissions.can("UI-15.download");var p=provider(upldId);return file(p==null?service.errorFile(upldId):p.errors(upldId));}
    @PostMapping(value="/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PersonnelUploadModels.Result> createPersonnelUploadValidation(@RequestParam("form_type") String form,@RequestParam(required=false) String month,@RequestPart MultipartFile file){permissions.can("UI-02.upload");return ApiResponse.ok(service.validatePersonnel(form,month,file));}
    @GetMapping("/{uploadId}/issues") public ApiResponse<PageResult<PersonnelUploadModels.Issue>> listUploadIssues(@PathVariable long uploadId,@RequestParam(required=false) String severity,@RequestParam(required=false) String column,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="row,asc") String sort){permissions.can("UI-02.upload");var p=provider(uploadId);var paging=new DeveloperPaging(page,size,sort);return ApiResponse.ok(p==null?service.personnel().issues(uploadId,severity,column,paging):p.issues(uploadId,severity,column,paging));}
    @GetMapping("/{uploadId}/rows") public ApiResponse<?> listUploadRows(@PathVariable long uploadId,@RequestParam(required=false) String severity,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="row,asc") String sort){permissions.can("UI-02.upload");var p=provider(uploadId);var paging=new DeveloperPaging(page,size,sort);return ApiResponse.ok(p==null?service.personnel().rows(uploadId,severity,paging):p.rows(uploadId,severity,paging));}
    @PostMapping("/{uploadId}/revalidate") public ApiResponse<PersonnelUploadModels.Result> createUploadRevalidation(@PathVariable long uploadId){permissions.can("UI-02.upload");var p=provider(uploadId);return ApiResponse.ok(p==null?service.personnel().revalidate(uploadId):p.revalidate(uploadId));}
    @PostMapping("/{uploadId}/apply") public ApiResponse<PersonnelUploadModels.Application> saveUploadApplication(@PathVariable long uploadId){permissions.can("UI-02.upload");if(provider(uploadId)!=null)throw new ApiException(400,"VALIDATION_ERROR","근태 반영에서 confirm=true로 확인해 주세요.","confirm");return ApiResponse.ok(service.personnel().apply(uploadId));}
    @GetMapping("/{uploadId}/errors") public ResponseEntity<byte[]> getPersonnelUploadErrorFile(@PathVariable long uploadId){permissions.can("UI-02.upload");var p=provider(uploadId);return file(p==null?service.personnel().errors(uploadId):p.errors(uploadId));}
}
