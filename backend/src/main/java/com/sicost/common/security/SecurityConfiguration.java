package com.sicost.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean public org.springframework.web.servlet.config.annotation.WebMvcConfigurer securityHandlers(PermissionService permissions) {
        return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
            @Override public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerAuthorizationInterceptor(permissions)).addPathPatterns("/api/**");
            }
        };
    }
    @Bean public org.springframework.security.core.userdetails.UserDetailsService noImplicitUsers() {
        return username -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("로그인이 필요합니다."); };
    }
    @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http,SessionService sessions,ObjectMapper json) throws Exception {
        var csrf=new CookieCsrfTokenRepository();
        csrf.setHeaderName("X-CSRF-TOKEN");
        csrf.setCookieCustomizer(cookie->cookie.httpOnly(true).secure(true).sameSite("Lax").path("/"));
        http.securityMatcher("/api/**")
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(SecurityConfiguration::nonBrowserJsonLogin))
            .authorizeHttpRequests(a->a
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/health","/api/auth/csrf").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/auth/login","/api/auth/mfa/verify","/api/auth/mfa/resend","/api/auth/forgot-password","/api/auth/reset-password").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e->e
                .authenticationEntryPoint((request,response,ex)->SessionAuthenticationFilter.writeError(response,request,json,new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null)))
                .accessDeniedHandler((request,response,ex)->SessionAuthenticationFilter.writeError(response,request,json,AuthorizationService.forbidden())))
            .addFilterBefore(new SessionAuthenticationFilter(sessions,json),CsrfFilter.class);
        return http.build();
    }
    private static boolean nonBrowserJsonLogin(jakarta.servlet.http.HttpServletRequest request) {
        // The runtime login contract has no CSRF preflight. Browser requests keep
        // CSRF protection, including same-origin fetches and opaque/null origins.
        if(!"POST".equals(request.getMethod()) ||
            !"/api/auth/login".equals(request.getRequestURI().substring(request.getContextPath().length())) ||
            request.getHeader("Origin")!=null || request.getHeader("Sec-Fetch-Site")!=null) return false;
        try {
            var type=org.springframework.http.MediaType.parseMediaType(request.getContentType());
            return "application".equals(type.getType()) && "json".equals(type.getSubtype());
        } catch(IllegalArgumentException ex) { return false; }
    }
}
