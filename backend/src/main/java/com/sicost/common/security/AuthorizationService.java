package com.sicost.common.security;

import com.sicost.common.api.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.security.SecurityModels.*;

@Service
@Transactional(readOnly=true)
public class AuthorizationService {
    private static final List<String> ACTIONS=List.of("read","create","update","delete","upload","download","confirm");
    private static final Map<String,String> ALIASES=Map.of("view","read","export","download","reopen","confirm",
        "cancel","confirm","recommend","create","decision","update","ask","read");
    private final SecurityStateMapper mapper;
    private final CurrentUser current;
    private final Clock clock;
    public AuthorizationService(SecurityStateMapper mapper,CurrentUser current,Clock clock) {
        this.mapper=mapper;this.current=current;this.clock=clock;
    }
    public ScopePredicate require(String key) { return scope(key); }
    public ScopePredicate scope(String key) {
        var principal=current.principal();
        if(principal.passwordChangeOnly()) throw forbidden();
        Account account=mapper.account(principal.userId());
        if(account==null || !"ACTIVE".equals(account.status()) ||
            (account.resignationDate()!=null && !LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).isBefore(account.resignationDate())))
            throw new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.",null);
        if(account.passwordChangeRequired()) throw forbidden();
        int dot=key==null?-1:key.indexOf('.');
        if(dot<0) throw forbidden();
        String screen=key.substring(0,dot),action=key.substring(dot+1);
        action=ALIASES.getOrDefault(action,action);
        int bit=ACTIONS.indexOf(action);
        if(bit<0) throw forbidden();
        final int selected=bit;
        boolean administration=List.of("UI-23","UI-24","UI-25").contains(screen);
        List<Permission> grants=mapper.permissions(account.userId()).stream()
            .filter(g->screen.equals(g.screenId()) && g.actions()!=null && g.actions().length()==7 &&
                g.actions().charAt(0)=='Y' && g.actions().charAt(selected)=='Y' && (!administration || g.admin()) &&
                (!"경영진".equals(g.roleName()) || selected==0 || selected==5)).toList();
        if(grants.isEmpty()) throw forbidden();
        var assignments=new Assignments(mapper.parts(account.userId()),mapper.projects(account.userId()));
        var result=new ScopePredicate(account.userId(),account.developerId(),assignments,grants);
        if(grants.stream().noneMatch(g->g.scope()==DataScope.ALL ||
            (g.scope()==DataScope.PART && !assignments.partIds().isEmpty()) ||
            (g.scope()==DataScope.PROJECT && !assignments.projectIds().isEmpty()) || g.scope()==DataScope.SELF)) throw forbidden();
        return result;
    }
    public ScopePredicate requireRow(String key,Resource row) {
        var scope=scope(key);
        if(!scope.allows(row)) throw ApiException.notFound("요청한 항목이 없습니다.");
        return scope;
    }
    public void requirePartStandard(Resource row) {
        var scope=requireRow("UI-15.update",row);
        if(scope.grants().stream().noneMatch(g->g.partStandardChange() && scope.allows(g,row))) throw forbidden();
    }
    public void requireAll(String key,List<Resource> rows) {
        var scope=scope(key);
        if(rows==null || rows.stream().anyMatch(r->!scope.allows(r))) throw forbidden();
    }
    public static ApiException forbidden() { return new ApiException(403,"FORBIDDEN","접근 권한이 없습니다.",null); }
}
