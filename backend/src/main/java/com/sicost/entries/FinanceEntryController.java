package com.sicost.entries;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.entries.FinanceEntryModels.*;

@RestController
public class FinanceEntryController {
    private final FinanceEntryService service;
    private final ObjectReader reader;
    public FinanceEntryController(FinanceEntryService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String body) {
        if(body==null)throw FinanceEntryInput.invalid(null);
        try{return reader.readTree(body);}catch(JsonProcessingException e){throw FinanceEntryInput.invalid(null);}
    }
    @GetMapping("/api/finance/entries")
    public ApiResponse<EntryList> listFinanceEntries(@RequestParam Map<String,String> params) {
        int year=FinanceEntryInput.integer(params.get("year"),"year",9999);
        int month=FinanceEntryInput.integer(params.get("month"),"month",12);
        return ApiResponse.ok(service.list(String.format("%04d-%02d",year,month)));
    }
    @PostMapping("/api/finance/entries")
    public ApiResponse<FinanceEntry> createFinanceEntry(@RequestBody(required=false) String body) {
        return ApiResponse.ok(service.create(FinanceEntryInput.parse(body(body))));
    }
    @PutMapping("/api/finance/entries/{id}")
    public ApiResponse<FinanceEntry> updateFinanceEntry(@PathVariable String id,@RequestBody(required=false) String body) {
        return ApiResponse.ok(service.update(FinanceEntryInput.id(id,"id"),FinanceEntryInput.parse(body(body))));
    }
    @DeleteMapping("/api/finance/entries/{id}")
    public ApiResponse<EntryRemoval> deleteFinanceEntry(@PathVariable String id) {
        return ApiResponse.ok(service.delete(FinanceEntryInput.id(id,"id")));
    }
    @PostMapping("/api/finance/entries/copy-previous")
    public ApiResponse<EntryPreview> previewPreviousFinanceEntries(@RequestParam Map<String,String> params,@RequestBody(required=false) String body) {
        String month=params.get("month");FinanceEntryInput.month(month,"month");
        String include=params.get("includeNonRecurring");
        if(!"true".equals(include)&&!"false".equals(include))throw FinanceEntryInput.invalid("includeNonRecurring");
        return ApiResponse.ok(service.preview(month,Boolean.parseBoolean(include),FinanceEntryInput.selections(body(body))));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> error(Exception ex,HttpServletRequest request) {
        var e=ex instanceof ApiException a?a:new ApiException(500,"INTERNAL_ERROR","서버 오류가 발생했습니다.",null);
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError(false,Map.of(),e.code(),e.getMessage(),e.field(),(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
