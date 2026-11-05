package com.sicost.systemmanagement;

import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class SystemManagementController {
    private final SystemManagementService service;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public SystemManagementController(SystemManagementService service,ExcelExportService excel,CurrentUser user,Clock clock) { this.service=service;this.excel=excel;this.user=user;this.clock=clock; }
    private Map<String,Object> query(Map<String,String> input,boolean history) {
        Set<String> allowed=history?Set.of("page","size","userId","from","to","type","result","screenId"):Set.of("page","size","name","loginId","partId","roleId","status");
        Map<String,Object> q=new HashMap<>();input.forEach((key,value)->{
            if(!allowed.contains(key)) throw ApiException.badRequest("검색 조건을 확인하세요.",key);
            if(value==null || value.isBlank()) return;
            try {
                Object v=switch(key) {
                    case "page","size" -> Integer.valueOf(value);
                    case "userId","partId","roleId" -> Long.valueOf(value);
                    case "from" -> LocalDate.parse(value).atStartOfDay();
                    case "to" -> LocalDate.parse(value).plusDays(1).atStartOfDay();
                    default -> value;
                };q.put(key,v);
            } catch(RuntimeException ex) { throw ApiException.badRequest("검색 조건을 확인하세요.",key); }
        });return q;
    }
    @GetMapping("/users") public ApiResponse<Map<String,Object>> users(@RequestParam Map<String,String> q) { return ApiResponse.ok(service.users(query(q,false))); }
    @GetMapping("/users/{id}") public ApiResponse<Map<String,Object>> user(@PathVariable Long id) { return ApiResponse.ok(service.user(id)); }
    @PostMapping("/users") public ApiResponse<Map<String,Object>> createUser(@RequestBody Map<String,Object> input) { var result=service.createUser(input);service.passwordReset(((Number)result.get("id")).longValue());return ApiResponse.ok(result); }
    @PatchMapping("/users/{id}") public ApiResponse<Map<String,Object>> updateUser(@PathVariable Long id,@RequestBody Map<String,Object> input) { return ApiResponse.ok(service.patchUser(id,input)); }
    @PostMapping("/users/{id}/disable") public ApiResponse<Void> disable(@PathVariable Long id) { service.disable(id);return ApiResponse.ok(); }
    @PostMapping("/users/{id}/unlock") public ApiResponse<Void> unlock(@PathVariable Long id) { service.unlock(id);return ApiResponse.ok(); }
    @PostMapping("/users/{id}/password-reset") public ApiResponse<Void> reset(@PathVariable Long id) { service.passwordReset(id);return ApiResponse.ok(); }
    @PutMapping("/users/{id}/roles") public ApiResponse<Void> roles(@PathVariable Long id,@RequestBody Map<String,Object> input) { service.roles(id,input);return ApiResponse.ok(); }
    @PutMapping("/users/{id}/assignments") public ApiResponse<Void> assignments(@PathVariable Long id,@RequestBody Map<String,Object> input) { service.assignments(id,input);return ApiResponse.ok(); }
    @GetMapping("/roles") public ApiResponse<List<Map<String,Object>>> roles() { return ApiResponse.ok(service.roles()); }
    @GetMapping("/roles/{id}") public ApiResponse<Map<String,Object>> role(@PathVariable Long id) { return ApiResponse.ok(service.role(id)); }
    @PostMapping("/roles") public ApiResponse<Map<String,Object>> createRole(@RequestBody Map<String,Object> input) { return ApiResponse.ok(service.createRole(input)); }
    @PatchMapping("/roles/{id}") public ApiResponse<Map<String,Object>> updateRole(@PathVariable Long id,@RequestBody Map<String,Object> input) { return ApiResponse.ok(service.patchRole(id,input)); }
    @PostMapping("/roles/{id}/copy") public ApiResponse<Map<String,Object>> copy(@PathVariable Long id,@RequestBody Map<String,Object> input) { return ApiResponse.ok(service.copyRole(id,input)); }
    @GetMapping("/roles/{id}/permissions") public ApiResponse<List<Map<String,Object>>> permissions(@PathVariable Long id) { return ApiResponse.ok(service.permissions(id)); }
    @PutMapping("/roles/{id}/permissions") public ApiResponse<Void> permissions(@PathVariable Long id,@RequestBody Map<String,Object> input) { service.savePermissions(id,input);return ApiResponse.ok(); }
    @GetMapping("/menus") public ApiResponse<List<Map<String,Object>>> menus() { return ApiResponse.ok(service.menus()); }
    @PostMapping("/menus") public ApiResponse<Map<String,Object>> createMenu(@RequestBody Map<String,Object> input) { return ApiResponse.ok(service.createMenu(input)); }
    @PatchMapping("/menus/{id}") public ApiResponse<Void> menu(@PathVariable Long id,@RequestBody Map<String,Object> input) { service.patchMenu(id,input);return ApiResponse.ok(); }
    @PutMapping("/menus/reorder") public ApiResponse<Void> reorder(@RequestBody Map<String,Object> input) { service.reorder(input);return ApiResponse.ok(); }
    @GetMapping("/access-history") public ApiResponse<Map<String,Object>> history(@RequestParam Map<String,String> q) { return ApiResponse.ok(service.history(query(q,true))); }
    @GetMapping("/users/export") public ResponseEntity<byte[]> usersExport(@RequestParam Map<String,String> q) { return export("users",query(q,false)); }
    @GetMapping("/access-history/export") public ResponseEntity<byte[]> historyExport(@RequestParam Map<String,String> q) { return export("history",query(q,true)); }
    private ResponseEntity<byte[]> export(String kind,Map<String,Object> q) {
        List<Map<String,Object>> rows=service.exportRows(kind,q);
        List<String> keys="users".equals(kind)?List.of("loginId","name","status","lastLoginAt","resignDate"):List.of("createdAt","userId","loginId","type","result","screenId","target","ip");
        List<ExportColumn<Map<String,Object>>> columns=keys.stream().map(k->new ExportColumn<Map<String,Object>>(k,k,false,false,r->r.get(k))).toList();
        byte[] file=excel.create("현재 검색 조건",user.name()+" ("+user.userId()+")",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+kind+".xlsx\"").body(file);
    }
}
