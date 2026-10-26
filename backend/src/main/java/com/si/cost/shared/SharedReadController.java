package com.si.cost.shared;

import com.si.cost.common.api.ApiResponse;
import com.si.cost.common.user.DataScope;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.si.cost.shared.SharedReadModels.*;

@RestController
public class SharedReadController {
    private final SharedReadService service;
    public SharedReadController(SharedReadService service) { this.service = service; }

    @GetMapping("/api/menus")
    public ApiResponse<ApiResponse<List<MenuNode>>> menus() {
        return ApiResponse.ok(ApiResponse.ok(service.menus(DataScope.ALL)));
    }
    @GetMapping("/api/codes/{group}")
    public ApiResponse<ApiResponse<List<CodeOption>>> codeOptions(@PathVariable String group) {
        return ApiResponse.ok(ApiResponse.ok(service.codeOptions(group, DataScope.ALL)));
    }
    @GetMapping("/api/month-close")
    public ApiResponse<ApiResponse<List<MonthClose>>> monthClose(@RequestParam String from, @RequestParam String to) {
        return ApiResponse.ok(ApiResponse.ok(service.monthClose(from, to, DataScope.ALL)));
    }
    @GetMapping("/api/params/{key}")
    public ApiResponse<ApiResponse<EffectiveParam>> effectiveParam(@PathVariable String key, @RequestParam String month) {
        return ApiResponse.ok(ApiResponse.ok(service.effectiveParam(key, month, DataScope.ALL)));
    }
}
