package com.sicost.useraccounts;

import com.sicost.authentication.AuthenticationService;
import com.sicost.common.api.ApiResponse;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class UserAccountsController {
    private final ManagementService service;
    private final AuthenticationService authentication;
    public UserAccountsController(ManagementService service,AuthenticationService authentication) { this.service=service;this.authentication=authentication; }
    @GetMapping("/users") public ApiResponse<Map<String,Object>> listAdminUsers(@RequestParam Map<String,String> filters) {return ApiResponse.ok(service.users(filters));}
    @GetMapping("/users/{id}") public ApiResponse<Map<String,Object>> getAdminUser(@PathVariable long id) {return ApiResponse.ok(service.getUser(id));}
    @PostMapping("/users") public ApiResponse<Map<String,Object>> createAdminUser(@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response) {
        var created=service.createUser(body);
        @SuppressWarnings("unchecked") var row=(Map<String,Object>)created.get("user");
        authentication.requestReset(AuthenticationService.number(row,"id"),request,response);created.put("resetDeliveryRequested",true);return ApiResponse.ok(created);
    }
    @PatchMapping("/users/{id}") public ApiResponse<Map<String,Object>> updateAdminUser(@PathVariable long id,@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.patchUser(id,body));}
    @PutMapping("/users/{id}/roles") public ApiResponse<Map<String,Object>> saveAdminUserRoles(@PathVariable long id,@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.rolesForUser(id,body));}
    @PutMapping("/users/{id}/assignments") public ApiResponse<Map<String,Object>> saveAdminUserAssignments(@PathVariable long id,@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.assignments(id,body));}
    @PostMapping("/users/{id}/disable") public ApiResponse<Map<String,Object>> updateAdminUserDisabled(@PathVariable long id) {return ApiResponse.ok(service.userAction(id,"disable"));}
    @PostMapping("/users/{id}/unlock") public ApiResponse<Map<String,Object>> updateAdminUserUnlocked(@PathVariable long id) {return ApiResponse.ok(service.userAction(id,"unlock"));}
    @PostMapping("/users/{id}/password-reset") public ApiResponse<Void> createAdminUserPasswordReset(@PathVariable long id,HttpServletRequest request,HttpServletResponse response) {service.require("UI-23","update");authentication.requestReset(id,request,response);return ApiResponse.ok();}
    @GetMapping("/access-history") public ApiResponse<Map<String,Object>> listAccessHistory(@RequestParam Map<String,String> filters) {return ApiResponse.ok(service.history(filters));}
    public static ResponseEntity<byte[]> file(byte[] bytes,String name) {return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+".xlsx\"").body(bytes);}
    @GetMapping("/users/export") public ResponseEntity<byte[]> getAdminUsersExport(@RequestParam Map<String,String> filters) {return file(service.export(false,filters),"users");}
    @GetMapping("/access-history/export") public ResponseEntity<byte[]> getAccessHistoryExport(@RequestParam Map<String,String> filters) {return file(service.export(true,filters),"access-history");}
}
