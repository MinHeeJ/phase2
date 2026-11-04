package com.sicost.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AiSessionConfiguration {
    @Bean
    public FilterRegistrationBean<AiEnvironmentFilter> aiEnvironmentRegistration(AiAccessGuard access, ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new AiEnvironmentFilter(access, json));
        registration.setName("aiEnvironmentBoundary");
        registration.addUrlPatterns("/api/ai/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
    @Bean
    public CookieSameSiteSupplier aiSessionSameSite() {
        return CookieSameSiteSupplier.ofLax().whenHasName("JSESSIONID");
    }
    @Bean
    public ServletContextInitializer aiSessionHttpOnly() {
        // The servlet container keeps its standard name and enables Secure for HTTPS requests.
        return context -> context.getSessionCookieConfig().setHttpOnly(true);
    }
}
