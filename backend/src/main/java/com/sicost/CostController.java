package com.sicost;

import com.sicost.Access.DataScope;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CostController {
    private final CostService service;
    public CostController(CostService service) { this.service = service; }
    public record ListQuery(Integer page, Integer size, String sort) {}
    static Map<String, Object> success(Object data) { return Map.of("success", true, "meta", Map.of(), "data", data); }
    private Map<String, Object> page(List<Map<String, Object>> rows, ListQuery query, String defaultSort) {
        int page = query.page() == null ? 1 : query.page(); int size = query.size() == null ? 50 : query.size();
        if (page < 1 || !Set.of(20, 50, 100).contains(size)) throw ApiException.invalid("page", "페이지와 건수를 확인하세요.");
        if (query.sort() != null && !query.sort().equals(defaultSort)) throw ApiException.invalid("sort", "지원하지 않는 정렬입니다.");
        long start = (long) (page - 1) * size;
        int from = (int) Math.min(start, rows.size()); int to = Math.min(from + size, rows.size());
        return success(Map.of("items", rows.subList(from, to), "total", rows.size(), "page", page, "size", size));
    }
    @GetMapping("/health")
    public Map<String, Object> getHealth() { return Map.of("status", "UP", "success", true, "meta", Map.of()); }
    @GetMapping("/menus")
    public Map<String, Object> getMenus() { return success(service.menus(DataScope.ALL)); }
    @GetMapping("/month-close")
    public Map<String, Object> getMonthClose(@RequestParam String from, @RequestParam String to) { return success(service.months(from, to, DataScope.ALL)); }
    @GetMapping("/codes/{group}")
    public Map<String, Object> getCodeOptions(@PathVariable String group) { return success(service.options(group, DataScope.ALL)); }
    @GetMapping("/admin/code-groups")
    public Map<String, Object> listCodeGroups(@ModelAttribute ListQuery query) { return page(service.groups(DataScope.ALL), query, "sort_seq,asc"); }
    @PostMapping("/admin/code-groups")
    public Map<String, Object> createCodeGroup(@RequestBody Map<String, Object> row) { service.createGroup(row); return success(Map.of("saved", true)); }
    @GetMapping("/admin/code-groups/{group}/codes")
    public Map<String, Object> listManagedCodes(@PathVariable String group, @ModelAttribute ListQuery query) { return page(service.codes(group, DataScope.ALL), query, "sort_seq,asc"); }
    @PutMapping("/admin/code-groups/{group}/codes")
    public Map<String, Object> saveGroupCodes(@PathVariable String group, @RequestBody List<Map<String, Object>> rows) { service.saveCodes(group, rows); return success(Map.of("saved", true)); }
    @GetMapping("/admin/excel-maps")
    public Map<String, Object> listExcelMaps(@ModelAttribute ListQuery query) { return page(service.maps(DataScope.ALL), query, "excel_lbl_nm,asc"); }
    @PostMapping("/admin/excel-maps")
    public Map<String, Object> createExcelMap(@RequestBody Map<String, Object> row) { service.createMap(row); return success(Map.of("saved", true)); }
    @PatchMapping("/admin/excel-maps/{mapId}")
    public Map<String, Object> updateExcelMap(@PathVariable String mapId, @RequestBody Map<String, Object> row) { service.patchMap(mapId, row); return success(Map.of("saved", true)); }
    @GetMapping("/admin/code-history")
    public Map<String, Object> listCodeHistory(@RequestParam(required = false) String group, @ModelAttribute ListQuery query) { return page(service.history(group, DataScope.ALL), query, "chg_at,desc"); }
    @GetMapping("/admin/params")
    public Map<String, Object> listCurrentParams(@ModelAttribute ListQuery query) { return page(service.currentParams(DataScope.ALL), query, "oprt_std_id,asc"); }
    @GetMapping("/params/{key}")
    public Map<String, Object> getEffectiveParam(@PathVariable String key, @RequestParam String month) { return success(service.effective(key, month)); }
    @GetMapping("/admin/params/{key}/versions")
    public Map<String, Object> listParamVersions(@PathVariable String key, @ModelAttribute ListQuery query) { return page(service.versions(key, DataScope.ALL), query, "aply_bgn_ym,desc"); }
    @PutMapping("/admin/params/{key}/versions/{month}")
    public Map<String, Object> saveParamVersion(@PathVariable String key, @PathVariable String month, @RequestBody Map<String, Object> row) { service.saveParam(key, month, row); return success(Map.of("saved", true)); }
}
