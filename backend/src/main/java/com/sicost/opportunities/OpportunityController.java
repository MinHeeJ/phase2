package com.sicost.opportunities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.sicost.opportunities.OpportunityModels.*;

@RestController
public class OpportunityController {
    private final OpportunityService service;
    private final ObjectReader reader;
    public OpportunityController(OpportunityService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private Save input(String body) {
        try{return OpportunityInput.parse(reader.readTree(body));}
        catch(JsonProcessingException e){throw OpportunityInput.error(null);}
    }
    @GetMapping("/api/opportunities")
    public ApiResponse<Page> getOpportunities(@RequestParam Map<String,String> query){return ApiResponse.ok(service.list(query));}
    @GetMapping("/api/opportunities/{id:[0-9]+}")
    public ApiResponse<Detail> getOpportunity(@PathVariable long id,@RequestParam Map<String,String> query){
        if(!query.isEmpty()) { OpportunityQuery.of(query);throw OpportunityInput.error(query.keySet().iterator().next()); }
        return ApiResponse.ok(service.get(id));
    }
    @PostMapping("/api/opportunities")
    public ApiResponse<Detail> createOpportunity(@RequestBody String body){return ApiResponse.ok(service.create(input(body)));}
    @PutMapping("/api/opportunities/{id}")
    public ApiResponse<Detail> updateOpportunity(@PathVariable long id,@RequestBody String body){return ApiResponse.ok(service.update(id,input(body)));}
}
