package com.sicost.systemmanagement;

import com.sicost.common.api.ApiResponse;
import com.sicost.shared.SharedReadModels.MenuNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class NavigationController {
    private final NavigationService service;
    public NavigationController(NavigationService service) { this.service=service; }
    @GetMapping("/navigation") public ApiResponse<List<MenuNode>> navigation() { return ApiResponse.ok(service.navigation()); }
    @GetMapping("/me/permissions") public ApiResponse<Map<String,Object>> permissions() { return ApiResponse.ok(service.myPermissions()); }
}
