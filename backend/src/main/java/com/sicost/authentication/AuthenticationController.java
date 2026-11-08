package com.sicost.authentication;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthenticationController {
    private final AuthenticationService service;
    private final SessionService sessions;
    private final NavigationService navigation;
    public AuthenticationController(AuthenticationService service,SessionService sessions,NavigationService navigation) { this.service=service;this.sessions=sessions;this.navigation=navigation; }
    public record Login(String loginId,String password) { @Override public String toString(){return "Login[redacted]";} }
    public record Code(String code) { @Override public String toString(){return "Code[redacted]";} }
    public record Forgot(String loginId) {}
    public record Password(String currentPassword,String newPassword,String confirmPassword,String token) { @Override public String toString(){return "Password[redacted]";} }
    @GetMapping("/auth/csrf") public ApiResponse<?> csrf(HttpServletRequest request) {
        var csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());return ApiResponse.ok(Map.of("headerName",csrf.getHeaderName(),"csrfToken",csrf.getToken()));
    }
    @PostMapping("/auth/login") public ResponseEntity<ApiResponse<?>> createLogin(@RequestBody Login input,HttpServletRequest request) {
        var result=service.login(input.loginId(),input.password(),cookie(request,"JSESSIONID"),request.getRemoteAddr());if(result==null) throw AuthenticationService.failed();return response(result);
    }
    @PostMapping("/auth/mfa/verify") public ResponseEntity<ApiResponse<?>> validateMfa(@RequestBody Code input,HttpServletRequest request) { return response(service.verify(cookie(request,"MFA_CHALLENGE"),input.code(),cookie(request,"JSESSIONID"))); }
    @PostMapping("/auth/mfa/resend") public ResponseEntity<ApiResponse<?>> resend(HttpServletRequest request) {
        String challenge=cookie(request,"MFA_CHALLENGE");
        return response(new AuthenticationService.LoginResult("MFA_REQUIRED",null,challenge,service.resend(challenge)));
    }
    @PostMapping("/auth/forgot-password") public ApiResponse<?> forgot(@RequestBody Forgot input,HttpServletRequest request) { service.forgot(input.loginId(),request.getRemoteAddr());return ApiResponse.ok(Map.of("message","등록된 계정이면 재설정 안내를 발송합니다.")); }
    @PostMapping("/auth/reset-password") public ApiResponse<Void> reset(@RequestBody Password input) { service.reset(input.token(),input.newPassword(),input.confirmPassword());return ApiResponse.ok(); }
    @PostMapping("/auth/change-password") public ApiResponse<Void> change(@RequestBody Password input) { service.change(new CurrentUser().userId(),input.currentPassword(),input.newPassword(),input.confirmPassword());return ApiResponse.ok(); }
    @PostMapping("/auth/activity") public ApiResponse<Void> activity(HttpServletRequest request) { sessions.activity(cookie(request,"JSESSIONID"));return ApiResponse.ok(); }
    @PostMapping("/auth/logout") public ResponseEntity<ApiResponse<?>> logout(HttpServletRequest request) {
        sessions.revoke(cookie(request,"JSESSIONID"));return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,expired("JSESSIONID"),expired("MFA_CHALLENGE")).body(ApiResponse.ok());
    }
    @GetMapping("/auth/me") public ApiResponse<?> getCurrentUser() { return ApiResponse.ok(navigation.identity()); }
    @GetMapping("/navigation") public ApiResponse<?> getNavigation() { return ApiResponse.ok(navigation.menus()); }
    @GetMapping("/me/permissions") public ApiResponse<?> getMyPermissions() { return ApiResponse.ok(navigation.permissions()); }
    private ResponseEntity<ApiResponse<?>> response(AuthenticationService.LoginResult result) {
        var builder=ResponseEntity.ok();Map<String,Object> body=new HashMap<>();body.put("status",result.status());
        if(result.session()!=null) builder.header(HttpHeaders.SET_COOKIE,SessionService.cookie(result.session()).toString(),expired("MFA_CHALLENGE"));
        if(result.challenge()!=null) {
            builder.header(HttpHeaders.SET_COOKIE,ResponseCookie.from("MFA_CHALLENGE",result.challenge()).path("/api/auth").httpOnly(true).secure(true).sameSite("Lax").maxAge(300).build().toString());
            body.put("expiresAt",result.expiresAt());body.put("expiresInSeconds",300);
        }
        return builder.body(ApiResponse.ok(body));
    }
    private String expired(String name) { return ResponseCookie.from(name,"").path(name.equals("MFA_CHALLENGE")?"/api/auth":"/").httpOnly(true).secure(true).sameSite("Lax").maxAge(0).build().toString(); }
    private String cookie(HttpServletRequest request,String name) { if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(name.equals(cookie.getName())) return cookie.getValue();return null; }
}
