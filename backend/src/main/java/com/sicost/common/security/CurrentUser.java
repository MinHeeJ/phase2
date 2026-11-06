package com.sicost.common.security;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.sicost.common.api.ApiException;

@Component
public class CurrentUser {
    public SecurityPrincipal principal() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes &&
            attributes.getRequest().getUserPrincipal() instanceof SecurityPrincipal principal) return principal;
        throw ApiException.unauthorized();
    }
    public Long userId() { return principal().userId(); }
    public String name() { return principal().displayName(); }
    public List<Long> roleIds() { return principal().roleIds(); }
    public DataScope dataScope() { return principal().dataScope(); }
    public Long getUserId() { return userId(); }
    public String getName() { return name(); }
    public List<Long> getRoleIds() { return roleIds(); }
}
