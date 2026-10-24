package com.sicost.partners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
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
    private PartnerSave input(String body,boolean update) {
        try {return PartnerInput.parse(reader.readTree(body),update);}
        catch(JsonProcessingException ex) {throw ApiException.badRequest("입력값을 확인하세요.",null);}
    }
    @GetMapping("/api/partners")
    public ApiResponse<PageResult<PartnerListItem>> listPartners(@RequestParam(required=false) String kind,
        @RequestParam(required=false) String useYn,@RequestParam(required=false) String q,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) {
        return ApiResponse.ok(service.list(new PageRequest(page,size,sort),kind,useYn,q));
    }
    @GetMapping("/api/partners/{id}") public ApiResponse<PartnerDetail> getPartner(@PathVariable long id) {return ApiResponse.ok(service.get(id));}
    @PostMapping("/api/partners") public ApiResponse<PartnerDetail> createPartner(@RequestBody String body) {
        PartnerSave input=input(body,false);
        try {return ApiResponse.ok(service.create(input));}
        catch(DuplicateKeyException ex) {throw service.duplicate(input.biz_reg_no());}
    }
    @PutMapping("/api/partners/{id}") public ApiResponse<PartnerDetail> updatePartner(@PathVariable long id,@RequestBody String body) {return ApiResponse.ok(service.update(id,input(body,true)));}
}
