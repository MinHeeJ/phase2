package com.sicost.common.security;

import java.util.List;
import com.sicost.common.api.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public SecurityPrincipal principal() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated() ||
            !(authentication.getPrincipal() instanceof SecurityPrincipal principal))
            throw new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null);
        return principal;
    }
    public Long userId() { return principal().userId(); }
    public String name() { return principal().name(); }
    public List<Long> roleIds() { return principal().roleIds(); }
    /** Legacy coarse scope is deliberately conservative; use AuthorizationService.scope for row reads. */
    public DataScope dataScope() { principal(); return DataScope.SELF; }
    public Long getUserId() { return userId(); }
    public String getName() { return name(); }
    public List<Long> getRoleIds() { return roleIds(); }
}
