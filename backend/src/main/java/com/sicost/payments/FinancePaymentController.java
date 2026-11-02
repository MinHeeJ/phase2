package com.sicost.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.payments.FinancePaymentModels.*;

@RestController
public class FinancePaymentController {
    private final FinancePaymentService service;
    private final ObjectReader reader;
    public FinancePaymentController(FinancePaymentService service,ObjectMapper json) {
        this.service=service;reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String value) {
        try { return FinancePaymentInput.object(reader.readTree(value)); }
        catch(JsonProcessingException ex) { throw FinancePaymentInput.invalid(null); }
    }
    @GetMapping("/api/finance/payments")
    public ApiResponse<PaymentList> listFinancePayments(@RequestParam(required=false) String month) {
        return ApiResponse.ok(service.list(month));
    }
    @PostMapping("/api/finance/payments")
    public ApiResponse<Payment> createFinancePayment(@RequestBody String value) {
        return ApiResponse.ok(service.create(FinancePaymentInput.parse(body(value))));
    }
    @PutMapping("/api/finance/payments/{id}")
    public ApiResponse<Payment> updateFinancePayment(@PathVariable long id,@RequestBody String value) {
        return ApiResponse.ok(service.update(id,FinancePaymentInput.parse(body(value))));
    }
    @PostMapping("/api/finance/payments/generate")
    public ApiResponse<PaymentList> generateFinancePayments(@RequestParam(required=false) String month) {
        return ApiResponse.ok(service.generate(month));
    }
    @PostMapping("/api/finance/payments/{id}/complete")
    public ApiResponse<Payment> completeFinancePayment(@PathVariable long id,@RequestBody String value) {
        return ApiResponse.ok(service.complete(id,FinancePaymentInput.complete(body(value))));
    }
    @PostMapping("/api/finance/payments/{id}/reopen")
    public ApiResponse<Payment> reopenFinancePayment(@PathVariable long id,@RequestBody String value) {
        return ApiResponse.ok(service.reopen(id,FinancePaymentInput.text(body(value),"reason",500,true)));
    }
    @PostMapping("/api/finance/payments/{id}/carry")
    public ApiResponse<Payment> carryFinancePayment(@PathVariable long id,@RequestBody String value) {
        return ApiResponse.ok(service.carry(id,FinancePaymentInput.text(body(value),"month",7,true)));
    }
    @GetMapping("/api/finance/payments/export")
    public ResponseEntity<byte[]> exportFinancePayments(@RequestParam(required=false) String month,
            @RequestParam(required=false) List<Long> ids) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=payment-review.xlsx").body(service.export(month,ids));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception e,HttpServletRequest request) {
        int status=500;String code="INTERNAL_ERROR",message="서버 오류가 발생했습니다.",field=null;
        if(e instanceof ApiException a) {status=a.status();code=a.code();message=a.getMessage();field=a.field();}
        else if(e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException a) {
            status=400;code="VALIDATION_ERROR";message="입력값을 확인하세요.";field=a.getName();
        } else if(e instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            status=400;code="VALIDATION_ERROR";message="입력값을 확인하세요.";
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError(false,Map.of(),code,message,field,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
