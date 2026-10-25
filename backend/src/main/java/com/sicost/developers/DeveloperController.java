package com.sicost.developers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.validation.DeveloperManagementNumbers;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.developers.DeveloperModels.*;

@RestController
public class DeveloperController {
    private final DeveloperService service;
    private final ObjectReader reader;
    public DeveloperController(DeveloperService service,ObjectMapper json) {
        this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private DeveloperSave input(String body,boolean update) {
        try{return DeveloperInput.parse(reader.readTree(body),update);}
        catch(JsonProcessingException ex){throw ApiException.badRequest("입력값을 확인하세요.",null);}
    }
    private int integer(Map<String,String> query,String key,int fallback) {
        if(!query.containsKey(key))return fallback;
        try{return Integer.parseInt(query.get(key));}catch(NumberFormatException ex){throw ApiException.badRequest("입력값을 확인하세요.",key);}
    }
    @GetMapping("/api/developers")
    public ApiResponse<PageResult<DeveloperListItem>> listDevelopers(@RequestParam Map<String,String> query) {
        var allowed=Set.of("empType","job","status","q","use_yn","page","size","sort");
        query.keySet().forEach(k->{if(!allowed.contains(k))throw ApiException.badRequest("조회 조건을 확인하세요.",k);});
        var page=new DeveloperPaging(integer(query,"page",0),integer(query,"size",50),query.get("sort"));
        return ApiResponse.ok(service.list(page,new DeveloperQuery.Filters(query.get("empType"),query.get("job"),query.get("status"),query.get("q"),query.get("use_yn"))));
    }
    @GetMapping("/api/developers/{id}")
    public ApiResponse<DeveloperDetail> getDeveloper(@PathVariable long id){return ApiResponse.ok(service.get(id));}
    @PostMapping("/api/developers")
    public ResponseEntity<ApiResponse<DeveloperDetail>> createDeveloper(@RequestBody String body) {
        var input=input(body,false);
        try{return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(input)));}
        catch(DuplicateKeyException ex){throw DeveloperManagementNumbers.duplicate();}
    }
    @PutMapping("/api/developers/{id}")
    public ApiResponse<DeveloperSaveResult> updateDeveloper(@PathVariable long id,@RequestBody String body){return ApiResponse.ok(service.update(id,input(body,true)));}
}
