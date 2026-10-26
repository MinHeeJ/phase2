package com.si.cost.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.cost.common.api.*;
import com.si.cost.common.user.DataScope;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.si.cost.admin.AdminModels.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;
    private final ObjectMapper json;
    public AdminController(AdminService service, ObjectMapper json) { this.service = service; this.json = json; }
    @GetMapping("/code-groups")
    public ApiResponse<?> listCodeGroups(@RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size, @RequestParam(required=false) String sort) {
        return read(service.groups(PageRequest.of(page,size,sort,"sort_seq"), DataScope.ALL));
    }
    @GetMapping("/code-groups/{group}/codes")
    public ApiResponse<?> listManagedCodes(@PathVariable String group, @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size, @RequestParam(required=false) String sort) {
        return read(service.codes(group,PageRequest.of(page,size,sort,"sort_seq"),DataScope.ALL));
    }
    @GetMapping("/excel-maps")
    public ApiResponse<?> listExcelMaps(@RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size, @RequestParam(required=false) String sort) {
        return read(service.maps(PageRequest.of(page,size,sort,"excel_lbl_nm"),DataScope.ALL));
    }
    @GetMapping("/code-history")
    public ApiResponse<?> listCodeHistory(@RequestParam(required=false) String group, @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size, @RequestParam(required=false) String sort) {
        return read(service.history(group,PageRequest.of(page,size,sort == null ? "chg_at,desc" : sort,"chg_at"),DataScope.ALL));
    }
    @GetMapping("/params")
    public ApiResponse<?> listCurrentParams(@RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size, @RequestParam(required=false) String sort) {
        return read(service.currentParams(PageRequest.of(page,size,sort,"oprt_std_id"),DataScope.ALL));
    }
    @GetMapping("/params/{key}/versions")
    public ApiResponse<?> listParamVersions(@PathVariable String key, @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size, @RequestParam(required=false) String sort) {
        return read(service.versions(key,PageRequest.of(page,size,sort == null ? "aply_bgn_ym,desc" : sort,"aply_bgn_ym"),DataScope.ALL));
    }
    @PostMapping("/code-groups")
    public ApiResponse<Void> createCodeGroup(@RequestBody JsonNode body) {
        object(body, Set.of("cd_grp_id","cd_grp_nm","attr1_nm","attr2_nm","attr3_nm"), Set.of(), "");
        service.createGroup(convert(body,GroupCreate.class,"body")); return ApiResponse.ok();
    }
    @PutMapping("/code-groups/{group}/codes")
    public ApiResponse<Void> saveGroupCodes(@PathVariable String group, @RequestBody JsonNode body) {
        if (!body.isArray()) throw BusinessException.invalid("body");
        List<Code> rows = new ArrayList<>();
        for (int i=0;i<body.size();i++) {
            JsonNode row=body.get(i); String prefix="["+i+"].";
            object(row,Set.of("dtl_cd","cd_nm","sort_seq","use_yn","attr1_val","attr2_val","attr3_val","aply_bgn_dt"),Set.of("attr1_val","attr2_val","attr3_val","aply_bgn_dt"),prefix);
            JsonNode order=row.get("sort_seq");
            if (order != null && (!order.isIntegralNumber() || !order.canConvertToInt())) throw BusinessException.invalid(prefix+"sort_seq");
            rows.add(convert(row,Code.class,prefix+"aply_bgn_dt"));
        }
        service.saveCodes(group,rows); return ApiResponse.ok();
    }
    @PostMapping("/excel-maps")
    public ApiResponse<Void> createExcelMap(@RequestBody JsonNode body) {
        object(body,Set.of("excel_lbl_nm","trns_cd_grp_id","trns_cd","map_role_cd","rmrk_desc"),Set.of(),"");
        service.createMap(convert(body,MapCreate.class,"body")); return ApiResponse.ok();
    }
    @PatchMapping("/excel-maps/{mapId}")
    public ApiResponse<Void> updateExcelMap(@PathVariable String mapId,@RequestBody JsonNode body) {
        object(body,Set.of("map_role_cd","rmrk_desc","use_yn"),Set.of(),"");
        Map<String,Object> values=new LinkedHashMap<>(); body.fields().forEachRemaining(e -> values.put(e.getKey(),e.getValue().textValue()));
        service.patchMap(mapId,values); return ApiResponse.ok();
    }
    @PutMapping("/params/{key}/versions/{month}")
    public ApiResponse<Void> saveParamVersion(@PathVariable String key,@PathVariable String month,@RequestBody String body) {
        JsonNode value;
        try { value = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body); }
        catch (JsonProcessingException failure) { throw BusinessException.invalid("body"); }
        if (value == null) throw BusinessException.invalid("body");
        object(value,Set.of("num_val","txt_val"),Set.of(),"");
        service.saveParam(key,month,convert(value,ParamSave.class,"num_val")); return ApiResponse.ok();
    }
    private <T> ApiResponse<?> read(T value) { return ApiResponse.ok(ApiResponse.ok(value)); }
    private void object(JsonNode node,Set<String> allowed,Set<String> nullable,String prefix) {
        if (!node.isObject()) throw BusinessException.invalid(prefix+"body");
        node.fields().forEachRemaining(e -> {
            String field=e.getKey(); JsonNode value=e.getValue();
            if (!allowed.contains(field) || (value.isNull() && !nullable.contains(field))) throw BusinessException.invalid(prefix+field);
            if (!value.isNull() && !(field.equals("sort_seq") ? value.isIntegralNumber() : field.equals("num_val") ? value.isNumber() : value.isTextual())) throw BusinessException.invalid(prefix+field);
        });
    }
    private <T> T convert(JsonNode node,Class<T> type,String field) {
        try { return json.treeToValue(node,type); } catch (JsonProcessingException failure) { throw BusinessException.invalid(field); }
    }
}
