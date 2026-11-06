package com.sicost.businesscontacts;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BusinessContactController {
    private final BusinessContactService service;
    public BusinessContactController(BusinessContactService service) {this.service=service;}
    @GetMapping("/developers/{id}/contact") public ApiResponse<Map<String,Object>> getDeveloperContact(@PathVariable long id,@RequestParam String purpose) {return ApiResponse.ok(service.contact(true,id,purpose));}
    @GetMapping("/partners/{id}/contact") public ApiResponse<Map<String,Object>> getPartnerContact(@PathVariable long id,@RequestParam String purpose) {return ApiResponse.ok(service.contact(false,id,purpose));}
}
