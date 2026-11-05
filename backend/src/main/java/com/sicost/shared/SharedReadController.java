package com.sicost.shared;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.DataScope;
import com.sicost.shared.SharedReadModels.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SharedReadController {
    private final SharedReadService service;
    private final com.sicost.systemmanagement.NavigationService navigation;

    public SharedReadController(SharedReadService service,com.sicost.systemmanagement.NavigationService navigation) { this.service = service;this.navigation=navigation; }

    @GetMapping("/menus")
    public ApiResponse<List<MenuNode>> getMenus() {
        return ApiResponse.ok(navigation.navigation());
    }

    @GetMapping("/codes/{group}")
    public ApiResponse<List<CodeOption>> getCodeOptions(@PathVariable String group) {
        return ApiResponse.ok(service.codeOptions(group, DataScope.ALL));
    }

    @GetMapping("/month-close")
    public ApiResponse<List<MonthClose>> getMonthClose(@RequestParam String from, @RequestParam String to) {
        return ApiResponse.ok(service.monthClose(from, to, DataScope.ALL));
    }

    @GetMapping("/params/{key}")
    public ApiResponse<OperatingParam> getEffectiveParam(@PathVariable String key, @RequestParam String month) {
        return ApiResponse.ok(service.effectiveParam(key, month, DataScope.ALL));
    }
}
