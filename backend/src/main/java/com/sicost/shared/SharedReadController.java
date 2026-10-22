package com.sicost.shared;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.DataScope;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SharedReadController {
    private final SharedReadService service;

    public SharedReadController(SharedReadService service) { this.service = service; }

    @GetMapping("/api/menus")
    public ApiResponse<List<MenuNode>> getMenus() { return ApiResponse.ok(service.menus(DataScope.ALL)); }

    @GetMapping("/api/codes/{group}")
    public ApiResponse<List<CodeOption>> getCodeOptions(@PathVariable String group) {
        return ApiResponse.ok(service.codes(group, DataScope.ALL));
    }

    @GetMapping("/api/month-close")
    public ApiResponse<List<MonthClose>> getMonthClose(@RequestParam String from, @RequestParam String to) {
        return ApiResponse.ok(service.months(from, to, DataScope.ALL));
    }

    @GetMapping("/api/params/{key}")
    public ApiResponse<OperatingParam> getEffectiveParam(@PathVariable String key, @RequestParam String month) {
        return ApiResponse.ok(service.parameter(key, month, DataScope.ALL));
    }
}
