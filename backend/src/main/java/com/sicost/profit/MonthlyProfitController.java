package com.sicost.profit;

import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MonthlyProfitController {
    private final MonthlyProfitService service;
    public MonthlyProfitController(MonthlyProfitService service){this.service=service;}
    @GetMapping("/api/finance/profit/monthly")
    public ApiResponse<MonthlyProfitModels.Annual> getMonthlyFinanceProfit(@RequestParam(required=false) String year) {
        int value=MonthlyProfitInput.integer(year,"year",1,9999);
        try{return ApiResponse.ok(service.monthly(value));}
        catch(ApiException ex){throw ex;}catch(RuntimeException ex){throw internal();}
    }
    @GetMapping("/api/finance/profit/structure")
    public ApiResponse<java.util.Map<String,Object>> getFinanceProfitStructure(@RequestParam(required=false) String year,
            @RequestParam(required=false) String month,@RequestParam(required=false) String period) {
        int y=MonthlyProfitInput.integer(year,"year",1,9999),m=MonthlyProfitInput.integer(month,"month",1,12);
        String p=MonthlyProfitInput.period(period);
        try{return ApiResponse.ok(service.structure(y,m,p));}
        catch(ApiException ex){throw ex;}catch(RuntimeException ex){throw internal();}
    }
    private ApiException internal(){return new ApiException(500,"INTERNAL_ERROR","손익 자료를 불러오지 못했습니다.",null);}
}
