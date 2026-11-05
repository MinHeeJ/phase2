package com.sicost.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiError;
import com.sicost.common.api.RequestIdFilter;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableMethodSecurity
public class ApplicationSecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean org.springframework.security.core.userdetails.UserDetailsService applicationUserDetails() {
        return name -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Authentication required"); };
    }
    @Bean SecurityFilterChain applicationSecurity(HttpSecurity http,AccessPolicyMapper mapper,Clock clock,
                                                  ObjectMapper json,Environment environment)throws Exception {
        boolean production=ProductionSecurityGuard.isProduction(environment);
        var csrf=new CookieCsrfTokenRepository();
        csrf.setHeaderName("X-CSRF-TOKEN");csrf.setCookieName("XSRF-TOKEN");
        csrf.setCookieCustomizer(c->c.httpOnly(true).secure(production).sameSite("Lax").path("/"));
        http.sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(c->c.requireExplicitSave(true))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(request->request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")
                    && org.springframework.security.web.csrf.CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)))
            .authorizeHttpRequests(c->c
                .requestMatchers(HttpMethod.GET,"/api/health","/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/auth/login","/api/auth/forgot-password","/api/auth/reset-password",
                    "/api/auth/mfa/verify","/api/auth/mfa/resend","/api/auth/mfa/cancel").permitAll()
                .requestMatchers("/api/**").authenticated().anyRequest().permitAll())
            .exceptionHandling(c->c.authenticationEntryPoint((request,response,error)->{
                response.setStatus(401);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
                json.writeValue(response.getOutputStream(),new ApiError(false,Map.of(),"UNAUTHENTICATED","로그인이 필요합니다.",null,
                    (String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
            }).accessDeniedHandler((request,response,error)->{
                response.setStatus(403);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
                json.writeValue(response.getOutputStream(),new ApiError(false,Map.of(),"FORBIDDEN","허용된 업무 권한이 없습니다.",null,
                    (String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
            }))
            .addFilterBefore(new SessionAuthenticationFilter(mapper,clock,json),org.springframework.security.web.csrf.CsrfFilter.class);
        if(production) http.requiresChannel(c->c.requestMatchers("/api/**").requiresSecure());
        return http.build();
    }
}
