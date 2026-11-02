package com.sicost.cashflow;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.sicost.cashflow.CashFlowModels.*;

@RestController
public class CashFlowController {
    private final CashFlowService service;
    private final ObjectReader reader;
    public CashFlowController(CashFlowService service,ObjectMapper json){this.service=service;reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    private JsonNode body(String body){try{if(body==null)throw CashFlowInput.invalid(null);return reader.readTree(body);}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw CashFlowInput.invalid(null);}}
    @GetMapping("/api/finance/cashflow") public ApiResponse<Flow> getFinanceCashflow(@RequestParam(required=false) String year,@RequestParam(required=false) String mode){if(mode==null)throw CashFlowInput.invalid("mode");return ApiResponse.ok(service.flow(CashFlowInput.year(year),mode));}
    @GetMapping("/api/finance/cashflow/opening") public ApiResponse<Opening> getCashOpening(@RequestParam(required=false) String month){return ApiResponse.ok(service.opening(month));}
    @PutMapping("/api/finance/cashflow/opening") public ApiResponse<Opening> saveCashOpening(@RequestParam(required=false) String month,@RequestBody(required=false) String body){return ApiResponse.ok(service.saveOpening(CashFlowInput.opening(month,body(body))));}
    @GetMapping("/api/finance/cashflow/other-inflows") public ApiResponse<Inflows> getFinanceOtherInflows(){return ApiResponse.ok(service.inflows());}
    @PostMapping("/api/finance/cashflow/other-inflows") public ApiResponse<Inflow> postFinanceOtherInflows(@RequestBody(required=false) String body){return ApiResponse.ok(service.saveInflow(CashFlowInput.inflow(body(body),false),false));}
    @PutMapping("/api/finance/cashflow/other-inflows") public ApiResponse<Inflow> putFinanceOtherInflows(@RequestBody(required=false) String body){return ApiResponse.ok(service.saveInflow(CashFlowInput.inflow(body(body),true),true));}
    @ExceptionHandler(ApiException.class) public ResponseEntity<ApiError> application(ApiException e,HttpServletRequest request){return ResponseEntity.status(e.status()).body(new ApiError(false,Map.of(),e.code(),e.getMessage(),e.field(),(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));}
    @ExceptionHandler(Exception.class) public ResponseEntity<ApiError> unexpected(Exception e,HttpServletRequest request){return ResponseEntity.internalServerError().body(new ApiError(false,Map.of(),"INTERNAL_ERROR","서버 오류가 발생했습니다.",null,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));}
}
