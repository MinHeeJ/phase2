package com.sicost.financeentries;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.businessplan2.BusinessPlanUploadInput;
import com.sicost.common.api.*;
import com.sicost.entries.FinanceEntryInput;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.financeentries.FinanceEntriesUploadModels.*;

@RestController
public class FinanceEntriesUploadController {
    private final FinanceEntriesUploadService service;
    private final ObjectReader reader;
    public FinanceEntriesUploadController(FinanceEntriesUploadService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @PostMapping(value="/api/uploads/finance-entries/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Summary> validateFinanceEntriesUpload(@RequestPart(required=false) MultipartFile file) {
        return ApiResponse.ok(service.validate(file));
    }
    @PostMapping("/api/uploads/finance-entries/apply")
    public ApiResponse<Summary> applyFinanceEntriesUpload(@RequestBody(required=false) String body) {
        try {
            if(body==null)throw FinanceEntryInput.invalid(null);
            var input=BusinessPlanUploadInput.parse(reader.readTree(body));
            return ApiResponse.ok(service.apply(input.uploadId(),input.confirm()));
        } catch(JsonProcessingException e) {throw FinanceEntryInput.invalid(null);}
    }
    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<ApiError> error(Exception ex,jakarta.servlet.http.HttpServletRequest request) {
        ApiException safe;
        if(ex instanceof ApiException a) {
            safe=a;
            if(a.status()==400 && !"VALIDATION_ERROR".equals(a.code()))safe=FinanceEntryInput.invalid(a.field());
            if(a.status()>=500)safe=new ApiException(500,"INTERNAL_ERROR","서버 오류가 발생했습니다.",null);
        } else safe=new ApiException(500,"INTERNAL_ERROR","서버 오류가 발생했습니다.",null);
        return new ApiExceptionHandler().application(safe,request);
    }
}
