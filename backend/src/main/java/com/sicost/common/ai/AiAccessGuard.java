package com.sicost.common.ai;

import com.sicost.common.api.ApiException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AiAccessGuard {
    private static final Set<String> INTERNAL = Set.of("dev", "development", "local", "test");
    private final Environment environment;
    public AiAccessGuard(Environment environment) { this.environment = environment; }

    public void requireDevelopment() {
        String configured = environment.getProperty("sicost.ai.environment", "development");
        if (!INTERNAL.contains(configured.toLowerCase(Locale.ROOT)) ||
                Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> !INTERNAL.contains(p.toLowerCase(Locale.ROOT)))) {
            throw forbidden();
        }
    }
    public void requireAction(String permissionKey) {
        requireDevelopment();
        if (!AiActions.contains(permissionKey)) throw ApiException.badRequest("허용된 AI 동작이 아닙니다.", null);
        // Future authenticated role/data-scope enforcement joins here, never PermissionService.can(true).
    }
    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "현재 환경 또는 접근 범위에서는 이 AI 요청을 허용하지 않습니다.", null);
    }
}
