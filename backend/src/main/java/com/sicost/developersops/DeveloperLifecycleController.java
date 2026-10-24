package com.sicost.developersops;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.sicost.developersops.DeveloperLifecycleModels.*;

@RestController
@RequestMapping("/api/developers")
public class DeveloperLifecycleController {
    private final DeveloperLifecycleService service;
    private final ObjectReader reader;
    public DeveloperLifecycleController(DeveloperLifecycleService service,ObjectMapper json){this.service=service;this.reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    private void fields(Map<String,String> query,Set<String> allowed){for(String key:query.keySet())if(!allowed.contains(key))throw ApiException.badRequest("입력값을 확인하세요.",key);}
    private int number(Map<String,String> query,String key,int fallback){try{return query.containsKey(key)?Integer.parseInt(query.get(key)):fallback;}catch(NumberFormatException e){throw ApiException.badRequest("입력값을 확인하세요.",key);}}
    private DeveloperPaging paging(Map<String,String> query){return new DeveloperPaging(number(query,"page",0),number(query,"size",50),query.get("sort"));}
    @GetMapping("/options") public ApiResponse<List<DeveloperOption>> listDeveloperOptions(@RequestParam Map<String,String> query){fields(query,Set.of("q"));return ApiResponse.ok(service.options(query.get("q")));}
    @GetMapping("/{id}/assignments") public ApiResponse<PageResult<DeveloperAssignment>> listDeveloperAssignments(@PathVariable long id,@RequestParam Map<String,String> query){fields(query,Set.of("page","size","sort","status"));return ApiResponse.ok(service.assignments(id,paging(query),query.get("status")));}
    @GetMapping("/{id}/history") public ApiResponse<PageResult<DeveloperChange>> listDeveloperHistory(@PathVariable long id,@RequestParam Map<String,String> query){fields(query,Set.of("page","size","sort","item"));return ApiResponse.ok(service.history(id,paging(query),query.get("item")));}
    @GetMapping("/{id}/monthly-costs") public ApiResponse<PageResult<DeveloperMonthlyCost>> listDeveloperMonthlyCosts(@PathVariable long id,@RequestParam Map<String,String> query){fields(query,Set.of("page","size","sort","from","to"));return ApiResponse.ok(service.monthlyCosts(id,paging(query),query.get("from"),query.get("to")));}
    @PostMapping("/disable") public ApiResponse<DeveloperDisableResult> updateDevelopersDisabled(@RequestBody String body){
        JsonNode node;try{node=reader.readTree(body);}catch(Exception e){throw ApiException.badRequest("개발자를 선택하세요.","ids");}
        if(node==null||!node.isObject())throw ApiException.badRequest("개발자를 선택하세요.","ids");
        var keys=node.fieldNames();while(keys.hasNext()){String key=keys.next();if(!"ids".equals(key))throw ApiException.badRequest("입력값을 확인하세요.",key);}
        if(!node.path("ids").isArray())throw ApiException.badRequest("개발자를 선택하세요.","ids");
        var ids=new ArrayList<Long>();for(JsonNode id:node.path("ids")){if(!id.isIntegralNumber()||!id.canConvertToLong())throw ApiException.badRequest("식별자를 확인하세요.","ids");ids.add(id.longValue());}
        return ApiResponse.ok(service.disable(ids));
    }
    @PostMapping("/{id}/enable") public ApiResponse<Map<String,Object>> updateDeveloperEnabled(@PathVariable long id){return ApiResponse.ok(service.enable(id));}
    @GetMapping("/export") public ResponseEntity<byte[]> getDeveloperExport(@RequestParam Map<String,String> query){
        fields(query,Set.of("empType","job","status","q","use_yn"));
        var file=service.export(new DeveloperExportQuery(query.get("empType"),query.get("job"),query.get("status"),query.get("q"),query.get("use_yn"),null));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString()).body(file.bytes());
    }
}
