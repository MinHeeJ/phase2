package com.sicost.common.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSecurityGuard implements InitializingBean {
    private final Environment environment;
    public ProductionSecurityGuard(Environment environment) { this.environment=environment; }
    public static boolean isProduction(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(p->Set.of("prod","production").contains(p.toLowerCase(Locale.ROOT)));
    }
    @Override public void afterPropertiesSet() {
        if(!isProduction(environment)) return;
        for(String key:Set.of("DEV_AUTH_BYPASS","dev.auth.bypass","sicost.auth.dev-bypass","sicost.auth.bypass",
            "sicost.security.auth-bypass","sicost.security.test-fixtures")) {
            String value=environment.getProperty(key,"false");
            if(!Set.of("false","0","no","off","").contains(value.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Production authentication bypass is forbidden");
            }
        }
        if(Arrays.stream(environment.getActiveProfiles()).anyMatch(p->Set.of("test","local","dev","development").contains(p.toLowerCase(Locale.ROOT)))) {
            throw new IllegalStateException("Production cannot enable nonproduction fixtures");
        }
        if(!Set.of("none","NATIVE","native").contains(environment.getProperty("server.forward-headers-strategy","none"))) {
            throw new IllegalStateException("Production requires a container-managed trusted proxy boundary");
        }
    }
}
