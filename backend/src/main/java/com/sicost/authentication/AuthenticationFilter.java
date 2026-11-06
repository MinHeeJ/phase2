package com.sicost.authentication;

import com.sicost.common.api.*;
import com.sicost.common.security.SecurityPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(20)
public class AuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_GET=Set.of("/api/health","/api/auth/csrf");
    private static final Set<String> PUBLIC_POST=Set.of("/api/auth/login","/api/auth/mfa/verify","/api/auth/mfa/resend","/api/auth/mfa/cancel","/api/auth/forgot-password","/api/auth/reset-password");
    private final ObjectProvider<AuthenticationService> services;
    private final ObjectMapper json;
    public AuthenticationFilter(ObjectProvider<AuthenticationService> services,ObjectMapper json) { this.services=services;this.json=json; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        try {
            String path=request.getRequestURI(),method=request.getMethod();
            if(method.equals("GET") && PUBLIC_GET.contains(path)) { chain.doFilter(request,response);return; }
            AuthenticationService service=services.getIfAvailable();if(service==null) throw ApiException.unauthorized();
            boolean publicRequest=method.equals("POST") && PUBLIC_POST.contains(path);
            Map<String,Object> account=publicRequest?null:service.session(request);
            if(!Set.of("GET","HEAD","OPTIONS").contains(method)) service.verifyCsrf(request,account);
            if(account==null) { chain.doFilter(request,response);return; }
            if(Boolean.TRUE.equals(account.get("passwordChangeRequired")) && !Set.of("/api/auth/change-password","/api/auth/logout").contains(path)) throw ApiException.forbidden();
            SecurityPrincipal principal=service.principal(account);
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public Principal getUserPrincipal() { return principal; }
                @Override public String getRemoteUser() { return principal.getName(); }
            },response);
        } catch(ApiException ex) {
            response.setStatus(ex.status());response.setContentType("application/json");
            if(ex.status()==429) response.setHeader("Retry-After","60");
            json.writeValue(response.getOutputStream(),new ApiError(false,Map.of(),ex.code(),ex.getMessage(),ex.field(),(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
        }
    }
}
