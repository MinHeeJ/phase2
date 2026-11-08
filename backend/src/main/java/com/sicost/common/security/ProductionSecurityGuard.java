package com.sicost.common.security;

import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSecurityGuard implements InitializingBean {
    private static final Set<String> NON_PRODUCTION=Set.of("dev","development","local","test");
    private final Environment environment;
    public ProductionSecurityGuard(Environment environment) { this.environment=environment; }
    @Override public void afterPropertiesSet() {
        var profiles=environment.getActiveProfiles();
        boolean production=profiles.length==0 || Arrays.stream(profiles).anyMatch(p->!NON_PRODUCTION.contains(p));
        if(production) for(String key:ListHolder.UNSAFE_SETTINGS) {
            if(environment.getProperty(key,Boolean.class,false)) throw new IllegalStateException("운영 인증 우회 또는 시험 데이터 설정은 허용되지 않습니다.");
        }
    }
    private static final class ListHolder {
        private static final String[] UNSAFE_SETTINGS={"DEV_AUTH_BYPASS","dev.auth.bypass","sicost.security.dev-auth-bypass",
            "sicost.auth.bypass","sicost.security.enabled-bypass","sicost.security.synthetic-test-enabled","sicost.ai.synthetic-test-enabled"};
    }
}
