package com.sicost.menumanagement;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadModels.MenuNode;
import com.sicost.useraccounts.ManagementMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.authentication.AuthenticationService.*;

@Service
@Transactional(readOnly=true)
public class NavigationService {
    private final ManagementMapper mapper;
    private final PermissionService permissions;
    private final CurrentUser user;
    public NavigationService(ManagementMapper mapper,PermissionService permissions,CurrentUser user) {this.mapper=mapper;this.permissions=permissions;this.user=user;}
    private List<MenuNode> children(List<Map<String,Object>> rows,Long parent,boolean mobile,Set<Long> ancestors) {
        List<MenuNode> result=new ArrayList<>();
        for(var row:rows) {
            Long parentId=row.get("parentId")==null?null:number(row,"parentId");long id=number(row,"id");
            if(!Objects.equals(parent,parentId) || ancestors.contains(id) || !"Y".equals(row.get("useYn")) || (mobile && !Boolean.TRUE.equals(row.get("mobileVisible")))) continue;
            Set<Long> visited=new HashSet<>(ancestors);visited.add(id);var children=children(rows,id,mobile,visited);String screen=(String)row.get("screenId");
            if(screen!=null) {try {permissions.can(screen+".read");} catch(ApiException ex) {if(ex.status()==403) continue;throw ex;}}
            if(screen==null && children.isEmpty()) continue;
            result.add(new MenuNode(id,(String)row.get("name"),screen,(String)row.get("icon"),Boolean.TRUE.equals(row.get("mobileVisible")),children));
        }
        return result;
    }
    public List<MenuNode> menus(boolean mobile) {user.principal();return children(mapper.menus(),null,mobile,Set.of());}
    private List<String> leaves(List<MenuNode> nodes) {return nodes.stream().flatMap(node -> {var ids=new ArrayList<String>();if(node.screenId()!=null) ids.add(node.screenId());ids.addAll(leaves(node.children()));return ids.stream();}).toList();}
    public Map<String,Object> navigation(boolean mobile) {var menus=menus(mobile);var ids=leaves(menus);return values("menus",menus,"dashboardAllowed",ids.contains("UI-16"),"firstAllowedScreenId",ids.contains("UI-16")?"UI-16":ids.stream().findFirst().orElse(null),"permissionVersion",java.time.LocalDateTime.now(SecurityTime.SEOUL).toString());}
    public Map<String,Object> mine() {
        user.principal();var allowed=new ArrayList<Map<String,Object>>();
        for(var row:mapper.menus()) {
            String screen=(String)row.get("screenId");if(screen==null) continue;
            var entry=values("screenId",screen);boolean read=false;
            for(var action:PermissionService.AUTH_COLUMNS.keySet()) {try {permissions.can(screen+"."+action);entry.put(action,true);if(action.equals("read")) read=true;} catch(ApiException ex) {if(ex.status()!=403) throw ex;entry.put(action,false);}}
            if(read) allowed.add(entry);
        }
        return values("permissions",allowed,"menus",menus(false));
    }
}
