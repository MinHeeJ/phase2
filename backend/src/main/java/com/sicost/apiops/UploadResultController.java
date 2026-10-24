package com.sicost.apiops;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import static com.sicost.apiops.UploadResultModels.*;

@RestController
@RequestMapping("/api/uploads")
public class UploadResultController {
    private final UploadResultService service;

    public UploadResultController(UploadResultService service) { this.service = service; }

    @GetMapping("/{uploadId}")
    public ApiResponse<UploadResult> getUploadResult(@PathVariable String uploadId) {
        if (!uploadId.matches("[0-9]{1,19}")) {
            throw ApiException.badRequest("검사 결과 번호를 확인하세요.", "uploadId");
        }
        long id;
        try { id = Long.parseLong(uploadId); }
        catch (NumberFormatException ex) { throw ApiException.badRequest("검사 결과 번호를 확인하세요.", "uploadId"); }
        return ApiResponse.ok(service.get(id));
    }
}
