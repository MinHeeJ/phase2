package com.sicost.authentication;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.shared.*;
import com.sicost.shared.SharedReadModels.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class NavigationService {
    private final SharedReadMapper menus;
    private final SecurityStateMapper state;
    private final CurrentUser current;
    public NavigationService(SharedReadMapper menus,SecurityStateMapper state,CurrentUser current) { this.menus=menus;this.state=state;this.current=current; }
    private List<SecurityModels.Permission> grants() {
        var p=current.principal();if(p.passwordChangeOnly()) throw AuthorizationService.forbidden();return state.permissions(p.userId());
    }
    public List<MenuNode> menus() {
        var grants=grants();var rows=menus.menus();Set<String> allowed=new HashSet<>();
        grants.stream().filter(g->g.actions()!=null && g.actions().length()==7 && g.actions().charAt(0)=='Y' &&
            (!Set.of("UI-23","UI-24","UI-25").contains(g.screenId()) || g.admin())).forEach(g->allowed.add(g.screenId()));
        return children(null,rows,allowed,new HashSet<>());
    }
    private List<MenuNode> children(Long parent,List<MenuRow> rows,Set<String> allowed,Set<Long> visited) {
        List<MenuNode> result=new ArrayList<>();
        for(var row:rows) if(Objects.equals(parent,row.parentId()) && visited.add(row.menuId())) {
            var descendants=children(row.menuId(),rows,allowed,visited);
            if(row.screenId()!=null?allowed.contains(row.screenId()):!descendants.isEmpty()) result.add(new MenuNode(row.menuId(),row.name(),row.screenId(),row.icon(),row.mobile(),descendants));
        }
        return result;
    }
    public Map<String,Object> permissions() {
        var grants=grants();Map<String,Boolean> permissions=new LinkedHashMap<>();List<String> actions=List.of("read","create","update","delete","upload","download","confirm");
        for(var grant:grants) {
            if(grant.actions()==null || grant.actions().length()!=7 || grant.actions().charAt(0)!='Y') continue;
            if(Set.of("UI-23","UI-24","UI-25").contains(grant.screenId()) && !grant.admin()) continue;
            for(int i=0;i<7;i++) if(grant.actions().charAt(i)=='Y' && (!"경영진".equals(grant.roleName()) || i==0 || i==5)) permissions.put(grant.screenId()+"."+actions.get(i),true);
        }
        return Map.of("permissions",permissions,"menus",menus(),"scopes",grants.stream().map(g->g.scope().name()).distinct().toList());
    }
    public Map<String,Object> identity() {
        var p=current.principal();var grants=grants();return Map.of("userId",p.userId(),"name",p.name(),"roleIds",state.roles(p.userId()),"roles",grants.stream().map(SecurityModels.Permission::roleName).distinct().toList());
    }
}
