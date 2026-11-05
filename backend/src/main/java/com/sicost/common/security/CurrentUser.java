package com.sicost.common.security;

import java.util.List;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import com.sicost.common.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import static com.sicost.common.security.AccessPolicyModels.*;

@Component
public class CurrentUser {
    private AccessPolicyMapper mapper;
    private Clock clock;
    public CurrentUser() {}
    @Autowired
    public CurrentUser(AccessPolicyMapper mapper, Clock clock) { this.mapper=mapper; this.clock=clock; }
    public SessionPrincipal principal() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) throw unauthenticated();
        return principal;
    }
    public Account account() {
        var principal=principal();
        if(mapper==null || clock==null) throw unauthenticated();
        var account=mapper.activeSession(new SessionLookup(principal.userId(),principal.sessionIdHash(),
            LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")))));
        if(account==null) throw unauthenticated();
        return account;
    }
    public Account requireBusinessAccount() {
        var account=account();
        if(account.passwordChangeRequired()) throw new ApiException(403,"PASSWORD_CHANGE_REQUIRED","비밀번호를 먼저 변경하세요.",null);
        return account;
    }
    public Long userId() { return requireBusinessAccount().userId(); }
    public String name() { return requireBusinessAccount().name(); }
    public List<Role> roles() { return mapper.roles(userId()); }
    public List<Long> roleIds() { return roles().stream().map(Role::roleId).toList(); }
    /** A legacy summary only. Query authorization must use PermissionService.scope for each action. */
    public DataScope dataScope() {
        var roles=roles();
        if(roles.isEmpty()) throw new ApiException(403,"FORBIDDEN","허용된 업무 권한이 없습니다.",null);
        return roles.stream().allMatch(r->r.scope()==DataScope.ALL) ? DataScope.ALL : DataScope.SELF;
    }
    public static ApiException unauthenticated() { return new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null); }
    public Long getUserId() { return userId(); }
    public String getName() { return name(); }
    public List<Long> getRoleIds() { return roleIds(); }
}
