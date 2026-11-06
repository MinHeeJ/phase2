package com.sicost.menumanagement;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class NavigationController {
    private final NavigationService service;
    public NavigationController(NavigationService service) {this.service=service;}
    @GetMapping("/navigation") public ApiResponse<Map<String,Object>> getNavigation(@RequestParam(defaultValue="false") boolean mobile) {return ApiResponse.ok(service.navigation(mobile));}
    @GetMapping("/me/permissions") public ApiResponse<Map<String,Object>> getMyPermissions() {return ApiResponse.ok(service.mine());}
}
