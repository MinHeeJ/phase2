package com.sicost.common.security;

import java.util.Map;
import java.util.List;
import java.util.Set;
import com.sicost.common.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static com.sicost.common.security.AccessPolicyModels.*;

@Service
public class PermissionService {
    public static final Map<String, String> AUTH_COLUMNS = Map.of(
        "read", "inq_auth_yn", "create", "reg_auth_yn", "update", "mdfcn_auth_yn",
        "delete", "del_auth_yn", "upload", "upld_auth_yn", "download", "dwnld_auth_yn",
        "confirm", "cnfrm_auth_yn");

    private static final Set<String> ADMIN_SCREENS=Set.of("UI-23","UI-24","UI-25");
    private CurrentUser user;
    private AccessPolicyMapper mapper;
    public PermissionService() {}
    @Autowired
    public PermissionService(CurrentUser user,AccessPolicyMapper mapper) { this.user=user;this.mapper=mapper; }
    public boolean can(String key) { scope(key);return true; }
    public AuthorizedScope scope(String key) {
        if(user==null || mapper==null) throw CurrentUser.unauthenticated();
        var account=user.requireBusinessAccount();
        var request=parse(account.userId(),key);
        var grants=mapper.grants(request).stream().filter(g->policy(g,request)).toList();
        if(grants.isEmpty()) throw forbidden();
        return new AuthorizedScope(account.userId(),account.developerId(),mapper.parts(account.userId()),
            mapper.projects(account.userId()),grants);
    }
    public void requireTarget(String key,Target target) {
        if(!scope(key).permits(target)) throw ApiException.notFound("요청한 항목이 없습니다.");
    }
    public void requireAll(String key,List<Target> targets) {
        var scope=scope(key);
        if(targets==null || targets.stream().anyMatch(t->!scope.permits(t))) throw forbidden();
    }
    private boolean policy(Grant g,PermissionLookup q) {
        if(ADMIN_SCREENS.contains(q.screenId()) && !g.admin()) return false;
        if(g.builtIn() && g.roleId()==2L && !Set.of("read","download").contains(q.action())) return false;
        return !(g.builtIn() && g.roleId()==3L && Set.of("UI-23","UI-24","UI-25","UI-27").contains(q.screenId()));
    }
    private PermissionLookup parse(Long userId,String key) {
        if(key==null || !key.matches("UI-[0-9]{2}\\.[a-z]+")) throw forbidden();
        var parts=key.split("\\.");
        String action=switch(parts[1]) {
            case "view","ask","recommend" -> "read";
            case "export" -> "download";
            case "decision","receipt","remind" -> "update";
            case "cancel","reopen","approve" -> "confirm";
            default -> parts[1];
        };
        if(!AUTH_COLUMNS.containsKey(action)) throw forbidden();
        return new PermissionLookup(userId,parts[0],action);
    }
    public static ApiException forbidden() { return new ApiException(403,"FORBIDDEN","허용된 업무 권한이 없습니다.",null); }
}
