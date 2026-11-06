package com.sicost.menumanagement;

import com.sicost.common.api.ApiResponse;
import com.sicost.useraccounts.ManagementService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class MenuManagementController {
    private final ManagementService service;
    public MenuManagementController(ManagementService service) {this.service=service;}
    @GetMapping("/menus") public ApiResponse<Map<String,Object>> listAdminMenus(@RequestParam Map<String,String> filters) {return ApiResponse.ok(service.menus(filters));}
    @PostMapping("/menus") public ApiResponse<Map<String,Object>> createAdminMenu(@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.saveMenu(null,body));}
    @PatchMapping("/menus/{id}") public ApiResponse<Map<String,Object>> updateAdminMenu(@PathVariable long id,@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.saveMenu(id,body));}
    @PutMapping("/menus/reorder") public ApiResponse<Void> saveAdminMenuOrder(@RequestBody Map<String,Object> body) {service.reorder(body);return ApiResponse.ok();}
    @GetMapping("/notification-channels") public ApiResponse<Map<String,Object>> getNotificationChannels() {return ApiResponse.ok(service.channel());}
    @PutMapping("/notification-channels") public ApiResponse<Map<String,Object>> saveNotificationChannels(@RequestBody Map<String,Object> body) {return ApiResponse.ok(service.channel(body));}
}
