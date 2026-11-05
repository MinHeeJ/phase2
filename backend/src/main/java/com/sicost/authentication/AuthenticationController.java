package com.sicost.authentication;

import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import com.sicost.common.security.ProductionSecurityGuard;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import static com.sicost.authentication.AuthenticationModels.*;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final AuthenticationService service;
    private final PermissionService permissions;
    private final boolean secure;
    public AuthenticationController(AuthenticationService service,PermissionService permissions,Environment environment) {
        this.service=service;this.permissions=permissions;this.secure=ProductionSecurityGuard.isProduction(environment);
    }
    public record Mfa(String code) { @Override public String toString(){return "Mfa[redacted]";} }
    public record Forgot(String loginId) {}
    public record Activity(String activityType,String screenId) {}
    private void cookie(HttpServletResponse response,String name,String value,int age) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(name,value==null?"":value).path("/").httpOnly(true).secure(secure).sameSite("Lax").maxAge(age).build().toString());
    }
    private ApiResponse<LoginResult> result(AuthResult result,HttpServletResponse response) {
        cookie(response,"JSESSIONID",result.session(),result.session()==null?0:-1);
        cookie(response,"SICOST_PREAUTH",result.preauth(),result.preauth()==null?0:300);
        return ApiResponse.ok(result.result());
    }
    @GetMapping("/csrf") public ApiResponse<Map<String,String>> csrf(CsrfToken token) { return ApiResponse.ok(Map.of("headerName",token.getHeaderName(),"csrfToken",token.getToken())); }
    @GetMapping("/me") public ApiResponse<Map<String,Object>> me() { return ApiResponse.ok(service.me()); }
    @PostMapping("/login") public ApiResponse<LoginResult> login(@RequestBody Login input,@CookieValue(name="JSESSIONID",required=false) String previous,HttpServletRequest request,HttpServletResponse response) {
        service.rate("LOGIN",input.loginId()==null?"":input.loginId().trim(),request.getRemoteAddr());return result(service.login(input,previous),response);
    }
    @PostMapping("/mfa/verify") public ApiResponse<LoginResult> verify(@RequestBody Mfa input,@CookieValue(name="SICOST_PREAUTH",required=false) String binding,HttpServletResponse response) { return result(service.verify(binding,input.code()),response); }
    @PostMapping("/mfa/resend") public ApiResponse<LoginResult> resend(@CookieValue(name="SICOST_PREAUTH",required=false) String binding,HttpServletResponse response) { return result(service.resend(binding),response); }
    @PostMapping("/mfa/cancel") public ApiResponse<Void> cancel(@CookieValue(name="SICOST_PREAUTH",required=false) String binding,HttpServletResponse response) { service.cancel(binding);cookie(response,"SICOST_PREAUTH",null,0);return ApiResponse.ok(); }
    @PostMapping("/forgot-password") public ApiResponse<Map<String,String>> forgot(@RequestBody Forgot input,HttpServletRequest request) {
        service.rate("RESET",input.loginId()==null?"":input.loginId().trim(),request.getRemoteAddr());service.forgot(input.loginId());return ApiResponse.ok(Map.of("message","등록된 이메일로 안내를 보냈습니다."));
    }
    @PostMapping("/reset-password") public ApiResponse<Void> reset(@RequestBody Reset input,HttpServletResponse response) { service.reset(input);cookie(response,"JSESSIONID",null,0);return ApiResponse.ok(); }
    @PostMapping("/change-password") public ApiResponse<Void> change(@RequestBody PasswordChange input,HttpServletResponse response) { service.change(input);cookie(response,"JSESSIONID",null,0);return ApiResponse.ok(); }
    @PostMapping("/logout") public ApiResponse<Void> logout(HttpServletResponse response) { service.logout();cookie(response,"JSESSIONID",null,0);cookie(response,"SICOST_PREAUTH",null,0);return ApiResponse.ok(); }
    @PostMapping("/activity") public ApiResponse<Void> activity(@RequestBody Activity input) { service.activity(input.activityType(),input.screenId(),permissions);return ApiResponse.ok(); }
}
