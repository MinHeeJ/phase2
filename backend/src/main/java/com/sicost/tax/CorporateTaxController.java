package com.sicost.tax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.tax.CorporateTaxModels.*;

@RestController
public class CorporateTaxController {
    private final CorporateTaxService service;
    private final ObjectReader reader;
    public CorporateTaxController(CorporateTaxService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String value) {
        if(value==null) throw CorporateTaxInput.invalid(null);
        try{return reader.readTree(value);}catch(JsonProcessingException ex){throw CorporateTaxInput.invalid(null);}
    }
    @GetMapping("/api/finance/tax/plan")
    public ApiResponse<Plan> getTaxPlan(@RequestParam(required=false) String year) {
        return ApiResponse.ok(service.plan(CorporateTaxInput.year(year)));
    }
    @PutMapping("/api/finance/tax/plan")
    public ApiResponse<Plan> saveTaxPlan(@RequestParam(required=false) String year,@RequestBody(required=false) String value) {
        int parsed=CorporateTaxInput.year(year);
        return ApiResponse.ok(service.savePlan(parsed,CorporateTaxInput.plan(body(value),parsed)));
    }
    @GetMapping("/api/finance/tax/payments")
    public ApiResponse<Payments> getTaxPayments(@RequestParam(required=false) String year) {
        return ApiResponse.ok(service.payments(CorporateTaxInput.year(year)));
    }
    @PutMapping("/api/finance/tax/payments")
    public ApiResponse<Payments> saveTaxPayments(@RequestParam(required=false) String year,@RequestBody(required=false) String value) {
        return ApiResponse.ok(service.savePayments(CorporateTaxInput.year(year),CorporateTaxInput.payments(body(value))));
    }
}
