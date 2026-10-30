package com.sicost.paymentorders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.paymentorders.PaymentOrderModels.*;

@RestController
public class PaymentOrderController {
    private final PaymentOrderService service;
    private final ObjectReader reader;
    public PaymentOrderController(PaymentOrderService service, ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/finance/payment-orders")
    public ApiResponse<Orders> getPaymentOrders(@RequestParam(required=false) String month) {
        try {return ApiResponse.ok(service.list(month));}
        catch(ApiException ex) {throw ex;}
        catch(Exception ex) {throw internal();}
    }
    @PutMapping("/api/finance/payment-orders")
    public ApiResponse<Orders> savePaymentOrders(@RequestParam(required=false) String month,
            @RequestBody(required=false) String body) {
        PaymentOrderInput.month(month);
        try {return ApiResponse.ok(service.save(month,PaymentOrderInput.parse(reader.readTree(body==null?"null":body),month)));}
        catch(JsonProcessingException ex) {throw PaymentOrderInput.invalid("items");}
        catch(ApiException ex) {throw ex;}
        catch(Exception ex) {throw internal();}
    }
    private ApiException internal() {return new ApiException(500,"INTERNAL_ERROR","서버 오류가 발생했습니다.",null);}
}
