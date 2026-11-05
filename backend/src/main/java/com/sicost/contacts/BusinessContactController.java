package com.sicost.contacts;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BusinessContactController {
    private final BusinessContactService service;
    public BusinessContactController(BusinessContactService service) { this.service=service; }
    @GetMapping("/developers/{id}/contact") public ApiResponse<Map<String,Object>> developer(@PathVariable Long id,@RequestParam String purpose) { return ApiResponse.ok(service.read("developer",id,purpose)); }
    @GetMapping("/partners/{id}/contact") public ApiResponse<Map<String,Object>> partner(@PathVariable Long id,@RequestParam String purpose) { return ApiResponse.ok(service.read("partner",id,purpose)); }
}
