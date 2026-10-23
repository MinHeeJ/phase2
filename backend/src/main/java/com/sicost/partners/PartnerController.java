package com.sicost.partners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.partners.PartnerModels.*;

@RestController
public class PartnerController {
    private final PartnerService service;
    private final ObjectReader reader;
    public PartnerController(PartnerService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @GetMapping("/api/partners") public ApiResponse<PageResult<PartnerListItem>> listPartners(@RequestParam Map<String,String> params) {return ApiResponse.ok(service.listPartners(params));}
    @GetMapping("/api/partners/{id}") public ApiResponse<PartnerDetail> getPartner(@PathVariable String id) {return ApiResponse.ok(service.getPartner(id));}
    @PostMapping("/api/partners") public ApiResponse<PartnerDetail> createPartner(@RequestBody String input) {return ApiResponse.ok(service.createPartner(body(input)));}
    @PutMapping("/api/partners/{id}") public ApiResponse<PartnerDetail> updatePartner(@PathVariable String id,@RequestBody String input) {return ApiResponse.ok(service.updatePartner(id,body(input)));}
    private JsonNode body(String input) {
        try {return reader.readTree(input);} catch(JsonProcessingException ex) {throw ApiException.badRequest("입력값을 확인하세요.",null);}
    }
}
