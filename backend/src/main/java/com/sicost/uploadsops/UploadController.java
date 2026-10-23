package com.sicost.uploadsops;

import com.sicost.common.api.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.uploadsops.UploadModels.*;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadService service;
    public UploadController(UploadService service){this.service=service;}
    @GetMapping("/templates/{form}") public ResponseEntity<byte[]> getUploadTemplate(@PathVariable String form){return file(service.template(form));}
    @PostMapping(value="/{form}/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Result> createUploadValidation(@PathVariable String form,@RequestPart MultipartFile file){return ApiResponse.ok(service.validate(form,file));}
    @PostMapping("/{upldId}/recheck") public ApiResponse<Result> saveUploadRecheck(@PathVariable String upldId){return ApiResponse.ok(service.recheck(upldId));}
    @PostMapping("/validated/{token}/apply") public ApiResponse<Applied> saveValidatedUploadApply(@PathVariable String token){return ApiResponse.ok(service.apply(token));}
    @GetMapping("/{upldId}/error-file") public ResponseEntity<byte[]> getUploadErrorFile(@PathVariable String upldId){return file(service.errorFile(upldId));}
    private ResponseEntity<byte[]> file(Download download){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(download.fileName(),StandardCharsets.UTF_8).build().toString()).body(download.bytes());}
}
