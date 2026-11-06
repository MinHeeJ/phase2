package com.sicost.rolepermissions;

import com.sicost.common.api.ApiResponse;
import com.sicost.useraccounts.ManagementService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/roles")
public class RolePermissionsController {
    private final ManagementService service;
    public RolePermissionsController(ManagementService service) {this.service=service;}
    @GetMapping public ApiResponse<Map<String,Object>> listAdminRoles(@RequestParam Map<String,String> filters) {return ApiResponse.ok(service.roles(filters));}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> getAdminRole(@PathVariable long id) {return ApiResponse.ok(service.role(id));}
    @PostMapping public ApiResponse<Map<String,Object>> createAdminRole(@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.saveRole(null,body));}
    @PatchMapping("/{id}") public ApiResponse<Map<String,Object>> updateAdminRole(@PathVariable long id,@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.saveRole(id,body));}
    @PostMapping("/{id}/copy") public ApiResponse<Map<String,Object>> createAdminRoleCopy(@PathVariable long id,@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.copyRole(id,body));}
    @GetMapping("/{id}/permissions") public ApiResponse<Map<String,Object>> getAdminRolePermissions(@PathVariable long id) {return ApiResponse.ok(service.rolePermissions(id));}
    @PutMapping("/{id}/permissions") public ApiResponse<Map<String,Object>> saveAdminRolePermissions(@PathVariable long id,@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.savePermissions(id,body));}
}
