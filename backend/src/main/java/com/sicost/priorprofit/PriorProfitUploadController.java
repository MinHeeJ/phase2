package com.sicost.priorprofit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.priorprofit.PriorProfitUploadModels.*;

@RestController
public class PriorProfitUploadController {
    private final PriorProfitUploadService service;
    private final ObjectReader reader;
    public PriorProfitUploadController(PriorProfitUploadService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @PostMapping(value="/api/uploads/prior-profit/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Summary> validatePriorProfitUpload(@RequestPart(required=false) MultipartFile file) {
        return ApiResponse.ok(service.validate(file));
    }
    @PostMapping("/api/uploads/prior-profit/apply")
    public ApiResponse<Summary> applyPriorProfitUpload(@RequestBody(required=false) String body) {
        try {
            if(body==null) throw PriorProfitUploadInput.invalid(null);
            var input=PriorProfitUploadInput.parse(reader.readTree(body));
            return ApiResponse.ok(service.apply(input.uploadId(),input.confirm()));
        } catch(JsonProcessingException e) {throw PriorProfitUploadInput.invalid(null);}
    }
    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<ApiError> error(Exception ex,jakarta.servlet.http.HttpServletRequest request) {
        ApiException safe=ex instanceof ApiException a?a:new ApiException(500,"INTERNAL_ERROR","서버 오류가 발생했습니다.",null);
        return new ApiExceptionHandler().application(safe,request);
    }
}
