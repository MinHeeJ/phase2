package com.sicost.partners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import static com.sicost.partners.PartnerModels.*;

@RestController
public class PartnerController {
    private final PartnerService service;
    private final ObjectReader reader;
    public PartnerController(PartnerService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String input) {
        try {return reader.readTree(input);}
        catch(JsonProcessingException e) {throw ApiException.badRequest("입력값을 확인하세요.",null);}
    }
    @GetMapping("/api/partners") public ApiResponse<PageResult<PartnerListItem>> listPartners(@RequestParam Map<String,String> query) {
        return ApiResponse.ok(service.list(query));
    }
    @GetMapping("/api/partners/{id}") public ApiResponse<PartnerDetail> getPartner(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }
    @PostMapping("/api/partners") public ApiResponse<PartnerDetail> createPartner(@RequestBody String body) {
        PartnerValue input=PartnerInput.parse(body(body),true);
        try {return ApiResponse.ok(service.create(input));}
        catch(DuplicateKeyException e) {throw service.duplicateConflict(input.biz_reg_no());}
    }
    @PutMapping("/api/partners/{id}") public ApiResponse<PartnerDetail> updatePartner(@PathVariable String id,@RequestBody String body) {
        return ApiResponse.ok(service.update(id,PartnerInput.parse(body(body),false)));
    }
}
