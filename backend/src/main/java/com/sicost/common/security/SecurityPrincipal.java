package com.sicost.common.security;

import java.security.Principal;
import java.util.List;

/** Authentication adapters construct this only after live account/session validation. */
public record SecurityPrincipal(long userId, String displayName, List<Long> roleIds,
                                DataScope dataScope) implements Principal {
    public SecurityPrincipal {
        if (userId <= 0 || displayName == null || dataScope == null) throw new IllegalArgumentException("인증 사용자를 확인하세요.");
        roleIds = List.copyOf(roleIds);
    }
    @Override public String getName() { return Long.toString(userId); }
}
