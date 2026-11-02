package com.sicost.businessplan;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.businessplan.BusinessPlanModels.*;

@RestController
public class BusinessPlanController {
    private final BusinessPlanService service;
    private final BusinessPlanComparisonService comparisons;
    private final ObjectReader reader;
    public BusinessPlanController(BusinessPlanService service,BusinessPlanComparisonService comparisons,ObjectMapper json) {
        this.service=service;this.comparisons=comparisons;
        this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/finance/business-plan")
    public ApiResponse<Plans> getBusinessPlan(@RequestParam(required=false) String year) {
        return ApiResponse.ok(service.list(BusinessPlanInput.year(year)));
    }
    @PutMapping("/api/finance/business-plan")
    public ApiResponse<Plans> saveBusinessPlan(@RequestParam(required=false) String year,@RequestBody(required=false) String body) {
        int selected=BusinessPlanInput.year(year);
        JsonNode input;
        try{input=body==null?null:reader.readTree(body);}catch(java.io.IOException ex){throw BusinessPlanInput.invalid("items");}
        return ApiResponse.ok(service.save(selected,BusinessPlanInput.parse(input,selected)));
    }
    @GetMapping("/api/finance/business-plan/comparison")
    public ApiResponse<Comparison> getBusinessPlanComparison(@RequestParam(required=false) String year,
            @RequestParam(required=false) String month,@RequestParam(required=false) String partId) {
        return ApiResponse.ok(comparisons.compare(BusinessPlanInput.year(year),BusinessPlanInput.month(month),BusinessPlanInput.part(partId)));
    }
    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<ApiError> error(Exception ex,jakarta.servlet.http.HttpServletRequest request) {
        ApiException safe=ex instanceof ApiException a?a:new ApiException(500,"INTERNAL_ERROR","서버 오류가 발생했습니다.",null);
        return new ApiExceptionHandler().application(safe,request);
    }
}
