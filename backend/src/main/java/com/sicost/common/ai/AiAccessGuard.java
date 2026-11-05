package com.sicost.common.ai;

import com.sicost.common.api.ApiException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;

@Component
public class AiAccessGuard {
    private final CurrentUser user;
    private final PermissionService permissions;
    public AiAccessGuard(Environment environment) { this(environment,new CurrentUser(),new PermissionService()); }
    @Autowired
    public AiAccessGuard(Environment environment,CurrentUser user,PermissionService permissions) {
        this.user=user;this.permissions=permissions;
    }

    public void requireDevelopment() {
        user.requireBusinessAccount();
    }
    public void requireAction(String permissionKey) {
        requireDevelopment();
        if (!AiActions.contains(permissionKey)) throw ApiException.badRequest("허용된 AI 동작이 아닙니다.", null);
        permissions.can(permissionKey);
    }
    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "현재 환경 또는 접근 범위에서는 이 AI 요청을 허용하지 않습니다.", null);
    }
}
