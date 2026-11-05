package com.sicost.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiError;
import com.sicost.common.api.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import static com.sicost.common.security.AccessPolicyModels.*;

public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> RESTRICTED_ALLOWED=Set.of("/api/auth/change-password","/api/auth/logout","/api/auth/csrf");
    private final AccessPolicyMapper mapper;
    private final Clock clock;
    private final ObjectMapper json;
    public SessionAuthenticationFilter(AccessPolicyMapper mapper,Clock clock,ObjectMapper json) {
        this.mapper=mapper;this.clock=clock;this.json=json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        SecurityContextHolder.clearContext();
        try {
            String path=request.getServletPath();
            if(path.isEmpty()) path=request.getRequestURI().substring(request.getContextPath().length());
            String raw=null;
            if(request.getCookies()!=null) for(var cookie:request.getCookies()) if("JSESSIONID".equals(cookie.getName())) {
                if(raw!=null) { error(response,request,401,"UNAUTHENTICATED");return; }
                raw=cookie.getValue();
            }
            if(raw!=null && raw.matches("[A-Za-z0-9_-]{43}")) {
                var lookup=new SessionLookup(null,AuthenticationProofs.hash(raw),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
                // The cookie never supplies a user ID; resolve the owner in the shared store first.
                var account=mapper.sessionOwner(lookup);
                if(account!=null) {
                    if(account.passwordChangeRequired() && !RESTRICTED_ALLOWED.contains(path)) {
                        error(response,request,403,"PASSWORD_CHANGE_REQUIRED");return;
                    }
                    var principal=new SessionPrincipal(account.userId(),lookup.sessionIdHash());
                    SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
                }
            }
            boolean publicEntry=("GET".equals(request.getMethod()) && Set.of("/api/health","/api/auth/csrf").contains(path))
                || ("POST".equals(request.getMethod()) && Set.of("/api/auth/login","/api/auth/forgot-password","/api/auth/reset-password",
                    "/api/auth/mfa/verify","/api/auth/mfa/resend","/api/auth/mfa/cancel").contains(path));
            if(path.startsWith("/api/") && !publicEntry && SecurityContextHolder.getContext().getAuthentication()==null) {
                error(response,request,401,"UNAUTHENTICATED");return;
            }
            chain.doFilter(request,response);
        } finally { SecurityContextHolder.clearContext(); }
    }
    private void error(HttpServletResponse response,HttpServletRequest request,int status,String code)throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(),new ApiError(false,Map.of(),code,
            status==401?"로그인이 필요합니다.":"비밀번호를 먼저 변경하세요.",null,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
