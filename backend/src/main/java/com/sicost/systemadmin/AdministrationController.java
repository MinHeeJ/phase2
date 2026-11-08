package com.sicost.systemadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.*;
import com.sicost.common.security.HandlerAuthorization;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdministrationController {
    private final AdministrationService service;
    public AdministrationController(AdministrationService service) { this.service=service; }
    @GetMapping("/users") @HandlerAuthorization("UI-23.read")
    public ApiResponse<?> listAdminUsers(@RequestParam Map<String,String> query) { return ApiResponse.ok(service.users(query)); }
    @GetMapping("/users/{id}") @HandlerAuthorization("UI-23.read")
    public ApiResponse<?> getAdminUser(@PathVariable Long id) { return ApiResponse.ok(service.user(id)); }
    @PostMapping("/users") @HandlerAuthorization("UI-23.create")
    public ApiResponse<?> createAdminUser(@RequestBody JsonNode body) { return ApiResponse.ok(service.createUser(body)); }
    @PatchMapping("/users/{id}") @HandlerAuthorization("UI-23.update")
    public ApiResponse<?> updateAdminUser(@PathVariable Long id,@RequestBody JsonNode body) { return ApiResponse.ok(service.patchUser(id,body)); }
    @PutMapping("/users/{id}/roles") @HandlerAuthorization("UI-23.update")
    public ApiResponse<?> saveAdminUserRoles(@PathVariable Long id,@RequestBody JsonNode body) { return ApiResponse.ok(service.userRoles(id,body)); }
    @PutMapping("/users/{id}/assignments") @HandlerAuthorization("UI-23.update")
    public ApiResponse<?> saveAdminUserAssignments(@PathVariable Long id,@RequestBody JsonNode body) { return ApiResponse.ok(service.assignments(id,body)); }
    @PostMapping("/users/{id}/disable") @HandlerAuthorization("UI-23.delete")
    public ApiResponse<?> disableAdminUser(@PathVariable Long id) { return ApiResponse.ok(service.disable(id)); }
    @PostMapping("/users/{id}/unlock") @HandlerAuthorization("UI-23.update")
    public ApiResponse<?> unlockAdminUser(@PathVariable Long id) { return ApiResponse.ok(service.unlock(id)); }
    @PostMapping("/users/{id}/password-reset") @HandlerAuthorization("UI-23.update")
    public ApiResponse<Void> createAdminUserPasswordReset(@PathVariable Long id) { service.reset(id);return ApiResponse.ok(); }
    @GetMapping("/users/export") @HandlerAuthorization("UI-23.download")
    public ResponseEntity<byte[]> getAdminUsersExport(@RequestParam Map<String,String> query) { return download(service.export(query,false),"users.xlsx"); }
    @GetMapping("/access-history") @HandlerAuthorization("UI-23.read")
    public ApiResponse<?> listAccessHistory(@RequestParam Map<String,String> query) { return ApiResponse.ok(service.history(query)); }
    @GetMapping("/access-history/export") @HandlerAuthorization("UI-23.download")
    public ResponseEntity<byte[]> getAccessHistoryExport(@RequestParam Map<String,String> query) { return download(service.export(query,true),"access-history.xlsx"); }
    @GetMapping("/roles") @HandlerAuthorization("UI-24.read")
    public ApiResponse<?> listAdminRoles() { return ApiResponse.ok(service.roles()); }
    @GetMapping("/roles/{id}") @HandlerAuthorization("UI-24.read")
    public ApiResponse<?> getAdminRole(@PathVariable Long id) { return ApiResponse.ok(service.role(id)); }
    @PostMapping("/roles") @HandlerAuthorization("UI-24.create")
    public ApiResponse<?> createAdminRole(@RequestBody JsonNode body) { return ApiResponse.ok(service.createRole(body)); }
    @PatchMapping("/roles/{id}") @HandlerAuthorization("UI-24.update")
    public ApiResponse<?> updateAdminRole(@PathVariable Long id,@RequestBody JsonNode body) { return ApiResponse.ok(service.patchRole(id,body)); }
    @PostMapping("/roles/{id}/copy") @HandlerAuthorization("UI-24.create")
    public ApiResponse<?> createAdminRoleCopy(@PathVariable Long id,@RequestBody JsonNode body) { return ApiResponse.ok(service.copyRole(id,body)); }
    @GetMapping("/roles/{id}/permissions") @HandlerAuthorization("UI-24.read")
    public ApiResponse<?> getAdminRolePermissions(@PathVariable Long id) { return ApiResponse.ok(service.permissions(id)); }
    @PutMapping("/roles/{id}/permissions") @HandlerAuthorization("UI-24.update")
    public ApiResponse<?> saveAdminRolePermissions(@PathVariable Long id,@RequestBody JsonNode body) { return ApiResponse.ok(service.savePermissions(id,body)); }
    @GetMapping("/menus") @HandlerAuthorization("UI-25.read")
    public ApiResponse<?> listAdminMenus() { return ApiResponse.ok(service.menus()); }
    @PostMapping("/menus") @HandlerAuthorization("UI-25.create")
    public ApiResponse<?> createAdminMenu(@RequestBody JsonNode body) { return ApiResponse.ok(service.createMenu(body)); }
    @PatchMapping("/menus/{id}") @HandlerAuthorization("UI-25.update")
    public ApiResponse<?> updateAdminMenu(@PathVariable Long id,@RequestBody JsonNode body) { return ApiResponse.ok(service.patchMenu(id,body)); }
    @PutMapping("/menus/reorder") @HandlerAuthorization("UI-25.update")
    public ApiResponse<?> saveAdminMenuOrder(@RequestBody JsonNode body) { return ApiResponse.ok(service.reorder(body)); }
    @GetMapping("/notification-channels") @HandlerAuthorization("UI-25.read")
    public ApiResponse<?> getNotificationChannels() { return ApiResponse.ok(service.channels()); }
    @PutMapping("/notification-channels") @HandlerAuthorization("UI-25.update")
    public ApiResponse<?> saveNotificationChannels(@RequestBody JsonNode body) { return ApiResponse.ok(service.channels(body)); }
    private ResponseEntity<byte[]> download(byte[] bytes,String name) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name).build().toString()).body(bytes);
    }
}
