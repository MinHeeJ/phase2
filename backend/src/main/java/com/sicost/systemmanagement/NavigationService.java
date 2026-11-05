package com.sicost.systemmanagement;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadMapper;
import com.sicost.shared.SharedReadModels.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class NavigationService {
    private final SharedReadMapper mapper;
    private final PermissionService permissions;
    private final CurrentUser user;
    public NavigationService(SharedReadMapper mapper,PermissionService permissions,CurrentUser user) { this.mapper=mapper;this.permissions=permissions;this.user=user; }
    public List<MenuNode> navigation() {
        user.requireBusinessAccount();List<MenuRow> rows=mapper.menus();
        return nodes(rows,null,new HashSet<>());
    }
    private List<MenuNode> nodes(List<MenuRow> rows,Long parent,Set<Long> visited) {
        return rows.stream().filter(r->Objects.equals(r.parentId(),parent) && !visited.contains(r.menuId())).map(r->{
            Set<Long> branch=new HashSet<>(visited);branch.add(r.menuId());List<MenuNode> children=nodes(rows,r.menuId(),branch);
            if(r.screenId()!=null && !allowed(r.screenId()+".read")) return null;
            if(r.screenId()==null && children.isEmpty()) return null;
            return new MenuNode(r.menuId(),r.name(),r.screenId(),r.icon(),r.mobile(),children);
        }).filter(Objects::nonNull).toList();
    }
    private boolean allowed(String key) {
        try { permissions.can(key);return true; } catch(ApiException ex) { if(ex.status()==403) return false;throw ex; }
    }
    public Map<String,Object> myPermissions() {
        List<MenuNode> menus=navigation();List<Map<String,Object>> grants=new ArrayList<>();collect(menus,grants);
        return Map.of("roles",user.roles(),"menus",menus,"permissions",grants);
    }
    private void collect(List<MenuNode> nodes,List<Map<String,Object>> grants) {
        for(var node:nodes) {
            if(node.screenId()!=null) {
                Map<String,Object> item=new LinkedHashMap<>();item.put("menuId",node.menuId());item.put("screenId",node.screenId());
                Map<String,String> names=Map.of("read","inq","create","reg","update","mdfcn","delete","del","upload","upld","download","dwnld","confirm","cnfrm");
                names.forEach((action,name)->item.put(name,allowed(node.screenId()+"."+action)));grants.add(item);
            }
            collect(node.children(),grants);
        }
    }
}
