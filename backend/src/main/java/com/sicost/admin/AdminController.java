package com.sicost.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.sicost.admin.AdminModels.*;
import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;
    private final CurrentUser user;
    private final ObjectMapper json;
    public AdminController(AdminService service, CurrentUser user, ObjectMapper json) { this.service=service; this.user=user; this.json=json; }
    public record AdminPage(Integer page, Integer size, String sort) {
        PageQuery query() { return PageQuery.of(page,size,sort); }
    }
    @GetMapping("/code-groups")
    public ApiResponse<PageData<CodeGroup>> listCodeGroups(@ModelAttribute AdminPage page) { return ApiResponse.ok(service.groups(page.query(),user.dataScope())); }
    @GetMapping("/code-groups/{group}/codes")
    public ApiResponse<PageData<CodeRow>> listManagedCodes(@PathVariable String group,@ModelAttribute AdminPage page) { return ApiResponse.ok(service.codes(group,page.query(),user.dataScope())); }
    @GetMapping("/excel-maps")
    public ApiResponse<PageData<ExcelMap>> listExcelMaps(@ModelAttribute AdminPage page) { return ApiResponse.ok(service.excelMaps(page.query(),user.dataScope())); }
    @GetMapping("/code-history")
    public ApiResponse<PageData<CodeChange>> listCodeHistory(@RequestParam(required=false) String group,@ModelAttribute AdminPage page) { return ApiResponse.ok(service.history(group,page.query(),user.dataScope())); }
    @GetMapping("/params")
    public ApiResponse<PageData<OperatingParam>> listCurrentParams(@ModelAttribute AdminPage page) { return ApiResponse.ok(service.params(page.query(),user.dataScope())); }
    @GetMapping("/params/{key}/versions")
    public ApiResponse<PageData<OperatingParam>> listParamVersions(@PathVariable String key,@ModelAttribute AdminPage page) { return ApiResponse.ok(service.versions(key,page.query(),user.dataScope())); }
    @PostMapping("/code-groups")
    public ApiResponse<Void> createCodeGroup(@RequestBody JsonNode body) { service.createGroup(bind(body,CodeGroupCreate.class,"")); return ApiResponse.ok(); }
    @PutMapping("/code-groups/{group}/codes")
    public ApiResponse<Void> saveGroupCodes(@PathVariable String group,@RequestBody JsonNode body) {
        if(!body.isArray()) throw ApiException.invalid("codes","전체 코드를 입력하세요.");
        List<CodeSaveRow> rows=new ArrayList<>();
        for(int i=0;i<body.size();i++) rows.add(bind(body.get(i),CodeSaveRow.class,"["+i+"]."));
        service.saveCodes(group,rows); return ApiResponse.ok();
    }
    @PostMapping("/excel-maps")
    public ApiResponse<Void> createExcelMap(@RequestBody JsonNode body) { service.createExcel(bind(body,ExcelMapCreate.class,"")); return ApiResponse.ok(); }
    @PatchMapping("/excel-maps/{mapId}")
    public ApiResponse<Void> updateExcelMap(@PathVariable String mapId,@RequestBody JsonNode body) { service.patchExcel(mapId,body); return ApiResponse.ok(); }
    @PutMapping("/params/{key}/versions/{month}")
    public ApiResponse<Void> saveParamVersion(@PathVariable String key,@PathVariable String month,@RequestBody String body) {
        try { service.saveParam(key,month,json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body)); }
        catch(JsonProcessingException e) { throw ApiException.invalid("body","입력 형식을 확인하세요."); }
        return ApiResponse.ok();
    }
    private <T> T bind(JsonNode node,Class<T> type,String prefix) {
        if(node==null || !node.isObject()) throw ApiException.invalid(prefix+"body","입력값을 확인하세요.");
        node.fields().forEachRemaining(e->{
            if(e.getKey().equals("sort_seq")) {
                if(!e.getValue().isIntegralNumber() || !e.getValue().canConvertToInt()) throw ApiException.invalid(prefix+e.getKey(),"입력 형식을 확인하세요.");
            } else if((e.getValue().isNull() && type != CodeSaveRow.class) || (!e.getValue().isNull() && !e.getValue().isTextual())) throw ApiException.invalid(prefix+e.getKey(),"입력 형식을 확인하세요.");
        });
        try { return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(node); }
        catch(UnrecognizedPropertyException e) { throw ApiException.invalid(prefix+e.getPropertyName(),"변경할 수 없는 항목입니다."); }
        catch(java.io.IOException e) { throw ApiException.invalid(prefix+"body","입력 형식을 확인하세요."); }
    }
}
