package com.sicost.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;
    private final CurrentUser user;
    private final ObjectReader reader;
    public AdminController(AdminService service, CurrentUser user, ObjectMapper json) {
        this.service=service;this.user=user;
        this.reader=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private JsonNode body(String input) {
        try { return reader.readTree(input); }
        catch(JsonProcessingException ex) { throw ApiException.badRequest("입력값을 확인하세요.",null); }
    }
    private ApiResponse<PageResult<?>> list(String resource,int page,int size,String sort,String group,String key) {
        return ApiResponse.ok(service.list(resource,new PageRequest(page,size,sort),group,key,user.dataScope()));
    }
    @GetMapping("/code-groups") public ApiResponse<PageResult<?>> listCodeGroups(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) { return list("groups",page,size,sort,null,null); }
    @GetMapping("/code-groups/{group}/codes") public ApiResponse<PageResult<?>> listManagedCodes(@PathVariable String group,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) { return list("codes",page,size,sort,group,null); }
    @GetMapping("/excel-maps") public ApiResponse<PageResult<?>> listExcelMaps(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) { return list("maps",page,size,sort,null,null); }
    @GetMapping("/code-history") public ApiResponse<PageResult<?>> listCodeHistory(@RequestParam(required=false) String group,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) { return list("history",page,size,sort,group,null); }
    @GetMapping("/params") public ApiResponse<PageResult<?>> listCurrentParams(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) { return list("params",page,size,sort,null,null); }
    @GetMapping("/params/{key}/versions") public ApiResponse<PageResult<?>> listParamVersions(@PathVariable String key,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String sort) { return list("versions",page,size,sort,null,key); }
    @PostMapping("/code-groups") public ApiResponse<Void> createCodeGroup(@RequestBody String input) { service.createGroup(AdminInput.group(body(input)));return ApiResponse.ok(); }
    @PutMapping("/code-groups/{group}/codes") public ApiResponse<Void> saveGroupCodes(@PathVariable String group,@RequestBody String input) { service.saveCodes(group,AdminInput.codes(body(input)));return ApiResponse.ok(); }
    @PostMapping("/excel-maps") public ApiResponse<Void> createExcelMap(@RequestBody String input) { service.createMapping(AdminInput.mapping(body(input)));return ApiResponse.ok(); }
    @PatchMapping("/excel-maps/{mapId}") public ApiResponse<Void> updateExcelMap(@PathVariable String mapId,@RequestBody String input) { service.patchMapping(mapId,AdminInput.patch(body(input)));return ApiResponse.ok(); }
    @PutMapping("/params/{key}/versions/{month}") public ApiResponse<Void> saveParamVersion(@PathVariable String key,@PathVariable String month,@RequestBody String input) { service.saveParam(key,month,AdminInput.value(body(input)));return ApiResponse.ok(); }
}
