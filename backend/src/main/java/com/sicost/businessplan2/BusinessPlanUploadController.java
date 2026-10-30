package com.sicost.businessplan2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.businessplan2.BusinessPlanUploadModels.*;

@RestController
public class BusinessPlanUploadController {
    private final BusinessPlanUploadService service;
    private final ObjectReader reader;
    public BusinessPlanUploadController(BusinessPlanUploadService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @PostMapping(value="/api/uploads/business-plan/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Summary> validateBusinessPlanUpload(@RequestPart(required=false) MultipartFile file) {
        return ApiResponse.ok(service.validate(file));
    }
    @PostMapping("/api/uploads/business-plan/apply")
    public ApiResponse<Summary> applyBusinessPlanUpload(@RequestBody(required=false) String body) {
        try {
            if(body==null) throw BusinessPlanUploadInput.invalid(null);
            var input=BusinessPlanUploadInput.parse(reader.readTree(body));
            return ApiResponse.ok(service.apply(input.uploadId(),input.confirm()));
        } catch(JsonProcessingException e) {throw BusinessPlanUploadInput.invalid(null);}
    }
    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<ApiError> error(Exception ex,jakarta.servlet.http.HttpServletRequest request) {
        ApiException safe=ex instanceof ApiException a?a:new ApiException(500,"INTERNAL_ERROR","서버 오류가 발생했습니다.",null);
        return new ApiExceptionHandler().application(safe,request);
    }
}
