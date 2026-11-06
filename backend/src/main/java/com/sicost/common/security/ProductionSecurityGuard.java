package com.sicost.common.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSecurityGuard implements InitializingBean {
    private final Environment environment;
    public ProductionSecurityGuard(Environment environment) { this.environment = environment; }
    @Override public void afterPropertiesSet() {
        boolean production = Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));
        if (!production) return;
        if (!environment.getProperty("sicost.security.secure-cookie",Boolean.class,true))
            throw new IllegalStateException("운영 환경에서는 Secure 쿠키가 필수입니다.");
        for (String key : List.of("DEV_AUTH_BYPASS","dev.auth.bypass","dev-auth-bypass",
                "sicost.security.dev-auth-bypass","security.auth-bypass","AUTH_BYPASS",
                "sicost.security.test-fixtures-enabled","security.test-fixtures-enabled")) {
            String value = environment.getProperty(key);
            if (value != null && !List.of("false","0","no","off","").contains(value.trim().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalStateException("운영 환경에서 인증 우회 또는 시험 데이터 설정을 사용할 수 없습니다.");
        }
    }
}
