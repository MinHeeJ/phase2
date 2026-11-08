package com.sicost.common.security;

import java.security.Principal;
import java.util.List;

public record SecurityPrincipal(Long userId, String name, List<Long> roleIds, String sessionHash,
                                boolean passwordChangeOnly) implements Principal {
    public SecurityPrincipal { roleIds=List.copyOf(roleIds); }
    @Override public String getName() { return name; }
    @Override public String toString() { return "SecurityPrincipal[userId="+userId+"]"; }
}
