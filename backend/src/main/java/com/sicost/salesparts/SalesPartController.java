package com.sicost.salesparts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.salesparts.SalesPartModels.*;

@RestController
public class SalesPartController {
    private final SalesPartService service;
    private final ObjectReader reader;
    public SalesPartController(SalesPartService service,ObjectMapper json) {
        this.service=service;
        this.reader=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/sales-parts")
    public ApiResponse<PageResult<SalesPartListItem>> listSalesParts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) {
        return ApiResponse.ok(service.listSalesParts(new PageRequest(page,size,sort)));
    }
    @GetMapping("/api/sales-parts/{id}")
    public ApiResponse<SalesPartDetail> getSalesPart(@PathVariable String id) { return ApiResponse.ok(service.getSalesPart(id)); }
    @PostMapping("/api/sales-parts")
    public ApiResponse<SalesPartDetail> createSalesPart(@RequestBody String input) {
        JsonNode body;
        try { body=reader.readTree(input); }
        catch(JsonProcessingException ex) { throw ApiException.badRequest("입력값을 확인하세요.",null); }
        return ApiResponse.ok(service.createSalesPart(SalesPartInput.create(body)));
    }
}
