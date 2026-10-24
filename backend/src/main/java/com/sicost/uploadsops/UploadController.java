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
    private ResponseEntity<byte[]> file(UploadFile file){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString()).body(file.bytes());}
    @GetMapping("/templates/{form}") public ResponseEntity<byte[]> getUploadTemplate(@PathVariable String form){permissions.can("UI-15.download");return file(service.template(form));}
    @PostMapping(value="/{form}/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<UploadValidationResult> createUploadValidation(@PathVariable String form,@RequestPart MultipartFile file){permissions.can("UI-15.upload");return ApiResponse.ok(service.validate(form,file));}
    @PostMapping("/{upldId}/recheck") public ApiResponse<UploadValidationResult> saveUploadRecheck(@PathVariable long upldId){permissions.can("UI-15.upload");return ApiResponse.ok(service.recheck(upldId));}
    @PostMapping("/validated/{token}/apply") public ApiResponse<UploadApplyResult> saveValidatedUploadApply(@PathVariable String token){permissions.can("UI-15.upload");return ApiResponse.ok(service.apply(token));}
    @GetMapping("/{upldId}/error-file") public ResponseEntity<byte[]> getUploadErrorFile(@PathVariable long upldId){permissions.can("UI-15.download");return file(service.errorFile(upldId));}
}
