package com.sicost.authentication;

import com.sicost.common.api.ApiResponse;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final AuthenticationService service;
    public AuthenticationController(AuthenticationService service) { this.service=service; }
    public record Login(String loginId,String password) {}
    public record Mfa(String code) {}
    public record Forgot(String loginId) {}
    public record Password(String currentPassword,String newPassword,String confirmPassword,String token) {}
    public record Activity(String activityType,String screenId) {}
    @GetMapping("/csrf") public ApiResponse<Map<String,Object>> getCsrfToken(HttpServletRequest request,HttpServletResponse response) { return ApiResponse.ok(service.csrf(request,response)); }
    @PostMapping("/login") public ApiResponse<Map<String,Object>> createLogin(@RequestBody Login body,HttpServletRequest request,HttpServletResponse response) { return ApiResponse.ok(service.login(body.loginId(),body.password(),request,response)); }
    @PostMapping("/mfa/verify") public ApiResponse<Map<String,Object>> validateMfa(@RequestBody Mfa body,HttpServletRequest request,HttpServletResponse response) { return ApiResponse.ok(service.mfa(body.code(),request,response)); }
    @PostMapping("/mfa/resend") public ApiResponse<Map<String,Object>> createMfaResend(HttpServletRequest request,HttpServletResponse response) { return ApiResponse.ok(service.resend(request,response)); }
    @PostMapping("/mfa/cancel") public ApiResponse<Void> saveMfaCancellation(HttpServletRequest request,HttpServletResponse response) { service.cancel(request,response);return ApiResponse.ok(); }
    @PostMapping("/logout") public ApiResponse<Void> saveLogout(HttpServletRequest request,HttpServletResponse response) { service.logout(request,response);return ApiResponse.ok(); }
    @GetMapping("/me") public ApiResponse<Map<String,Object>> getCurrentUser(HttpServletRequest request) { return ApiResponse.ok(service.me(request)); }
    @PostMapping("/forgot-password") public ApiResponse<Void> createPasswordResetRequest(@RequestBody Forgot body,HttpServletRequest request,HttpServletResponse response) { service.forgot(body.loginId(),request,response);return ApiResponse.ok(); }
    @PostMapping("/reset-password") public ApiResponse<Void> savePasswordReset(@RequestBody Password body,HttpServletRequest request,HttpServletResponse response) { service.password(body,true,request,response);return ApiResponse.ok(); }
    @PostMapping("/change-password") public ApiResponse<Void> updateCurrentPassword(@RequestBody Password body,HttpServletRequest request,HttpServletResponse response) { service.password(body,false,request,response);return ApiResponse.ok(); }
    @PostMapping("/activity") public ApiResponse<Void> saveUserActivity(@RequestBody Activity body,HttpServletRequest request) { service.activity(body.activityType(),body.screenId(),request);return ApiResponse.ok(); }
}
