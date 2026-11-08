package com.sicost.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiError;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_POSTS=Set.of("/api/auth/login","/api/auth/mfa/verify","/api/auth/mfa/resend",
        "/api/auth/forgot-password","/api/auth/reset-password");
    private final SessionService sessions;
    private final ObjectMapper json;
    public SessionAuthenticationFilter(SessionService sessions,ObjectMapper json) { this.sessions=sessions;this.json=json; }
    public static boolean publicEntry(String method,String path) {
        return ("GET".equals(method) && Set.of("/api/health","/api/auth/csrf").contains(path)) ||
            ("POST".equals(method) && PUBLIC_POSTS.contains(path));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        String origin=request.getHeader("Origin");
        String expected=request.getScheme()+"://"+request.getServerName()+
            ((request.isSecure() && request.getServerPort()==443) || (!request.isSecure() && request.getServerPort()==80)?"":":"+request.getServerPort());
        if(origin!=null && (origin.length()>200 || !origin.equals(expected))) {
            writeError(response,request,json,AuthorizationService.forbidden());return;
        }
        String raw=null;
        if(request.getCookies()!=null) for(Cookie cookie:request.getCookies()) if("JSESSIONID".equals(cookie.getName())) {
            if(raw!=null) { writeError(response,request,json,new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null));return; }
            raw=cookie.getValue();
        }
        try {
            if(raw!=null) {
                var principal=sessions.authenticate(raw);
                request.setAttribute(SessionService.class.getName()+".expiresAt",sessions.expiresAt(principal.sessionHash()));
                if(principal.passwordChangeOnly() && !Set.of("/api/auth/change-password","/api/auth/logout","/api/auth/csrf").contains(path)) throw AuthorizationService.forbidden();
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
                SecurityContextHolder.setContext(context);
            } else if(!publicEntry(request.getMethod(),path)) throw new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null);
        } catch(ApiException ex) {
            SecurityContextHolder.clearContext();
            // Expired cookies must not prevent obtaining a new CSRF token or logging in.
            if(!publicEntry(request.getMethod(),path) || ex.status()!=401) { writeError(response,request,json,ex);return; }
        }
        try { chain.doFilter(request,response); }
        finally { SecurityContextHolder.clearContext(); }
    }
    public static void writeError(HttpServletResponse response,HttpServletRequest request,ObjectMapper json,ApiException ex) throws IOException {
        response.setStatus(ex.status());response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(),new ApiError(false,Map.of(),ex.code(),ex.getMessage(),ex.field(),(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
