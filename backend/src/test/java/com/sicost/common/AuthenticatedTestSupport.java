package com.sicost.common;

import com.sicost.common.security.*;
import java.util.List;

public final class AuthenticatedTestSupport {
    private AuthenticatedTestSupport() {}
    public static SecurityPrincipal principal(long userId, List<Long> roles, DataScope scope) {
        return new SecurityPrincipal(userId,"합성 시험 사용자",roles,scope);
    }
}
