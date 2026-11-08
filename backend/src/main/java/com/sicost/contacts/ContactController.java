package com.sicost.contacts;

import com.sicost.common.api.ApiResponse;
import com.sicost.common.security.HandlerAuthorization;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ContactController {
    private final ContactService service;
    public ContactController(ContactService service) { this.service=service; }
    @GetMapping("/developers/{id}/contact") @HandlerAuthorization("UI-02.read")
    public ApiResponse<ContactService.ContactView> getDeveloperContact(@PathVariable Long id,@RequestParam String purpose) { return ApiResponse.ok(service.view(id,purpose,false)); }
    @GetMapping("/partners/{id}/contact") @HandlerAuthorization("UI-15.read")
    public ApiResponse<ContactService.ContactView> getPartnerContact(@PathVariable Long id,@RequestParam String purpose) { return ApiResponse.ok(service.view(id,purpose,true)); }
}
