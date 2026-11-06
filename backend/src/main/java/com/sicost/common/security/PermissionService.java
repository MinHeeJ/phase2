package com.sicost.common.security;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.sicost.common.api.ApiException;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    public static final Map<String, String> AUTH_COLUMNS = Map.of(
        "read", "inq_auth_yn", "create", "reg_auth_yn", "update", "mdfcn_auth_yn",
        "delete", "del_auth_yn", "upload", "upld_auth_yn", "download", "dwnld_auth_yn",
        "confirm", "cnfrm_auth_yn");

    private final CurrentUser user;
    private final java.util.function.Supplier<SecurityPermissionMapper> mapper;
    private final Clock clock;

    public PermissionService() {
        this.user = new CurrentUser(); this.mapper = () -> null; this.clock = Clock.system(SecurityTime.SEOUL);
    }
    @Autowired
    public PermissionService(CurrentUser user, ObjectProvider<SecurityPermissionMapper> mapper, Clock clock) {
        this.user = user; this.mapper = mapper::getIfAvailable; this.clock = clock;
    }
    /** Legacy callers discard the return value: denial MUST throw. */
    public boolean can(String key) { scope(key); return true; }

    public ScopePredicate scope(String key) {
        long userId = user.userId();
        if (key == null || !key.matches("UI-[0-9]{2}\\.[a-z]+")) throw ApiException.forbidden();
        int dot = key.indexOf('.');
        String column = AUTH_COLUMNS.get(key.substring(dot + 1));
        SecurityPermissionMapper source = mapper.get();
        if (column == null || source == null) throw ApiException.forbidden();
        var query = new SecurityPermissionMapper.Query(userId,key.substring(0,dot),column,SecurityTime.now(clock).toLocalDate());
        var rows = source.grants(query);
        if (rows.isEmpty()) throw ApiException.forbidden();
        Set<Long> parts = Set.copyOf(source.parts(userId));
        Set<Long> projects = Set.copyOf(source.projects(userId));
        List<ScopePredicate.Grant> grants = rows.stream().map(r -> new ScopePredicate.Grant(
            r.roleId(),DataScope.valueOf(r.scope()),parts,projects,r.sensitive(),r.partStandardChange())).toList();
        return new ScopePredicate(userId,rows.get(0).developerId(),grants);
    }
}
