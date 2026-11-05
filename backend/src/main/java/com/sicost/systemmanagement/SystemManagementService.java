package com.sicost.systemmanagement;

import com.sicost.authentication.AuthenticationService;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class SystemManagementService {
    private static final List<String> ACTIONS=List.of("inq","reg","mdfcn","del","upld","dwnld","cnfrm");
    private final SystemManagementMapper mapper;
    private final PermissionService permissions;
    private final CurrentUser user;
    private final LastAdministratorGuard administrators;
    private final PasswordEncoder encoder;
    private final AuthenticationService authentication;
    public SystemManagementService(SystemManagementMapper mapper,PermissionService permissions,CurrentUser user,
                                   LastAdministratorGuard administrators,PasswordEncoder encoder,AuthenticationService authentication) {
        this.mapper=mapper;this.permissions=permissions;this.user=user;this.administrators=administrators;this.encoder=encoder;this.authentication=authentication;
    }
    private void allow(String screen,String action) { permissions.can(screen+"."+action); }
    public Map<String,Object> users(Map<String,Object> query) {
        allow("UI-23","read");page(query);List<Map<String,Object>> rows=mapper.users(query);rows.forEach(this::relations);
        return pageResult(rows,mapper.userCount(query),query);
    }
    public Map<String,Object> user(Long id) { allow("UI-23","read");return relations(require(mapper.user(id))); }
    private Map<String,Object> relations(Map<String,Object> row) {
        Long id=((Number)row.get("id")).longValue();row.put("roleIds",mapper.userRoles(id));row.put("partIds",mapper.userParts(id));row.put("projectIds",mapper.userProjects(id));return row;
    }
    @Transactional public Map<String,Object> createUser(Map<String,Object> input) {
        allow("UI-23","create");only(input,"name","loginId","email","basePartId","partIds","projectIds","developerId","roleIds");
        text(input,"name",50,true);text(input,"loginId",50,true);email(input);
        if(!input.get("loginId").toString().matches("[A-Za-z0-9._-]{1,50}")) throw invalid("loginId");
        Long base=id(input.get("basePartId"),"basePartId");if(base==null || !mapper.partExists(base)) throw invalid("basePartId");
        List<Long> roles=ids(input,"roleIds",true);checkRoles(roles,false);
        Long developer=id(input.get("developerId"),"developerId");if(developer!=null && !mapper.developerExists(developer)) throw invalid("developerId");
        Map<String,Object> write=new HashMap<>(input);write.put("actor",user.userId());
        write.put("passwordHash",encoder.encode(AuthenticationProofs.randomSessionId()+"!1a"));
        Long created=mapper.createUser(write);replaceRelations(created,input,roles);audit("UI-23","USER_CREATE",created,null);
        return relations(require(mapper.user(created)));
    }
    @Transactional public Map<String,Object> patchUser(Long id,Map<String,Object> input) {
        allow("UI-23","update");administrators.lock();only(input,"name","email","phone","basePartId","resignDate","status");
        if(input.containsKey("basePartId") && input.get("basePartId")==null) throw invalid("basePartId");
        Map<String,Object> before=require(mapper.user(id));Map<String,Object> write=merge(before,input);validateUser(write);
        mapper.updateUser(write);administrators.requireRecovery();audit("UI-23","USER_CHANGE",id,null);return relations(require(mapper.user(id)));
    }
    private void validateUser(Map<String,Object> write) {
        text(write,"name",50,true);email(write);text(write,"phone",30,false);
        if(!Set.of("ACTIVE","LOCKED","DISABLED").contains(write.get("status"))) throw invalid("status");
        Long base=id(write.get("basePartId"),"basePartId");if(base!=null && !mapper.partExists(base)) throw invalid("basePartId");
        Object resign=write.get("resignDate");
        if(resign!=null) {
            LocalDate date;
            try { date=resign instanceof java.sql.Date d?d.toLocalDate():LocalDate.parse(resign.toString()); } catch(RuntimeException ex) { throw invalid("resignDate"); }
            write.put("resignDate",date);
            if(!date.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))) write.put("status","DISABLED");
        }
    }
    @Transactional public void disable(Long id) { allow("UI-23","delete");patchUser(id,Map.of("status","DISABLED")); }
    @Transactional public void unlock(Long id) {
        allow("UI-23","update");administrators.lock();require(mapper.user(id));mapper.unlock(id);audit("UI-23","UNLOCK",id,null);
    }
    public void passwordReset(Long id) { allow("UI-23","update");require(mapper.user(id));authentication.sendReset(id); }
    @Transactional public void roles(Long id,Map<String,Object> input) {
        allow("UI-23","update");administrators.lock();require(mapper.user(id));only(input,"roleIds","adminGrantConfirmed");
        List<Long> roles=ids(input,"roleIds",true);checkRoles(roles,Boolean.TRUE.equals(input.get("adminGrantConfirmed")) || mapper.userRoles(id).containsAll(roles));
        mapper.clearRoles(id);mapper.assignRoles(relation(id,roles));administrators.requireRecovery();audit("UI-23","PERM_CHANGE",id,null);
    }
    @Transactional public void assignments(Long id,Map<String,Object> input) {
        allow("UI-23","update");require(mapper.user(id));only(input,"partIds","projectIds");replaceAssignments(id,input);audit("UI-23","PERM_CHANGE",id,null);
    }
    private void replaceRelations(Long id,Map<String,Object> input,List<Long> roles) { mapper.assignRoles(relation(id,roles));replaceAssignments(id,input); }
    private void replaceAssignments(Long id,Map<String,Object> input) {
        List<Long> parts=ids(input,"partIds",false),projects=ids(input,"projectIds",false);
        if(parts.stream().anyMatch(p->!mapper.partExists(p))) throw invalid("partIds");
        if(projects.stream().anyMatch(p->!mapper.projectExists(p))) throw invalid("projectIds");
        mapper.clearParts(id);mapper.clearProjects(id);
        if(!parts.isEmpty()) mapper.assignParts(relation(id,parts));if(!projects.isEmpty()) mapper.assignProjects(relation(id,projects));
    }
    private Map<String,Object> relation(Long id,List<Long> ids) { return Map.of("id",id,"ids",ids,"actor",user.userId()); }
    private void checkRoles(List<Long> roles,boolean confirmed) {
        for(Long id:roles) { var role=require(mapper.role(id));if(!"Y".equals(role.get("useYn"))) throw invalid("roleIds");if(Boolean.TRUE.equals(role.get("admin")) && !confirmed) throw invalid("adminGrantConfirmed"); }
    }
    public List<Map<String,Object>> roles() { allow("UI-24","read");return mapper.roles(); }
    public Map<String,Object> role(Long id) { allow("UI-24","read");return require(mapper.role(id)); }
    @Transactional public Map<String,Object> createRole(Map<String,Object> input) {
        allow("UI-24","create");only(input,"name","description","useYn","dataScope","sensitiveView","partStandardChange","admin","adminGrantConfirmed");
        Map<String,Object> write=new HashMap<>(input);write.putIfAbsent("admin",false);write.putIfAbsent("sensitiveView",false);write.putIfAbsent("partStandardChange",false);write.putIfAbsent("useYn","Y");
        validateRole(write);write.put("actor",user.userId());Long id=mapper.createRole(write);audit("UI-24","PERM_CHANGE",id,"CREATE");return require(mapper.role(id));
    }
    @Transactional public Map<String,Object> patchRole(Long id,Map<String,Object> input) {
        allow("UI-24","update");administrators.lock();only(input,"name","description","useYn","dataScope","sensitiveView","partStandardChange","admin","adminGrantConfirmed");
        Map<String,Object> old=require(mapper.role(id));Map<String,Object> write=merge(old,input);
        if(Boolean.TRUE.equals(old.get("builtIn")) && (!Objects.equals(old.get("name"),write.get("name")) || !Objects.equals(old.get("admin"),write.get("admin")))) throw invalid("admin");
        if(Boolean.TRUE.equals(old.get("admin"))) write.put("adminGrantConfirmed",true);
        validateRole(write);mapper.updateRole(write);administrators.requireRecovery();audit("UI-24","PERM_CHANGE",id,"UPDATE");return require(mapper.role(id));
    }
    private void validateRole(Map<String,Object> row) {
        text(row,"name",50,true);text(row,"description",500,false);
        if(!Set.of("ALL","PART","PROJECT","SELF").contains(row.get("dataScope"))) throw invalid("dataScope");
        yn(row);for(String key:List.of("admin","sensitiveView","partStandardChange")) if(!(row.get(key) instanceof Boolean)) throw invalid(key);
        if(Boolean.TRUE.equals(row.get("admin")) && !Boolean.TRUE.equals(row.get("adminGrantConfirmed"))) throw invalid("adminGrantConfirmed");
    }
    @Transactional public Map<String,Object> copyRole(Long source,Map<String,Object> input) {
        allow("UI-24","create");only(input,"name","description");Map<String,Object> old=require(mapper.role(source));
        Map<String,Object> write=new HashMap<>(input);write.put("dataScope",old.get("dataScope"));write.put("useYn","Y");
        write.put("admin",false);write.put("sensitiveView",false);write.put("partStandardChange",false);validateRole(write);
        write.put("copyRoleId",source);write.put("actor",user.userId());Long id=mapper.createRole(write);
        mapper.copyPermissions(Map.of("id",id,"sourceId",source));audit("UI-24","PERM_CHANGE",id,"COPY");return require(mapper.role(id));
    }
    public List<Map<String,Object>> permissions(Long id) { allow("UI-24","read");require(mapper.role(id));return mapper.permissions(id); }
    @Transactional public void savePermissions(Long id,Map<String,Object> input) {
        allow("UI-24","update");administrators.lock();only(input,"permissions","reason");text(input,"reason",500,true);
        var role=require(mapper.role(id));Object raw=input.get("permissions");if(!(raw instanceof List<?> rows)) throw invalid("permissions");
        var menus=mapper.menus();Set<Long> seen=new HashSet<>();List<Map<String,Object>> writes=new ArrayList<>();
        for(Object item:rows) {
            if(!(item instanceof Map<?,?> supplied)) throw invalid("permissions");Map<String,Object> row=new HashMap<>();
            for(var e:supplied.entrySet()) { if(!(e.getKey() instanceof String key)) throw invalid("permissions");row.put(key,e.getValue()); }
            only(row,"menuId","inq","reg","mdfcn","del","upld","dwnld","cnfrm");Long menu=id(row.get("menuId"),"menuId");
            if(menu==null || !seen.add(menu)) throw invalid("menuId");
            var target=menus.stream().filter(m->((Number)m.get("id")).longValue()==menu).findFirst().orElseThrow(()->invalid("menuId"));
            for(String action:ACTIONS) if(!(row.get(action) instanceof Boolean)) throw invalid(action);
            boolean any=ACTIONS.stream().anyMatch(a->Boolean.TRUE.equals(row.get(a)));
            if(!Boolean.TRUE.equals(row.get("inq")) && any) throw invalid("inq");
            if(any && Set.of("UI-23","UI-24","UI-25").contains(Objects.toString(target.get("screenId"),"")) && !Boolean.TRUE.equals(role.get("admin"))) throw invalid("menuId");
            row.put("id",id);row.put("actor",user.userId());writes.add(row);
        }
        if(seen.size()!=menus.size()) throw invalid("permissions");
        writes.forEach(mapper::savePermission);administrators.requireRecovery();audit("UI-24","PERM_CHANGE",id,"권한 저장: "+input.get("reason"));
    }
    public List<Map<String,Object>> menus() { allow("UI-25","read");return mapper.menus(); }
    @Transactional public Map<String,Object> createMenu(Map<String,Object> input) {
        allow("UI-25","create");administrators.lock();only(input,"name","screenId","parentId","icon","sortOrder","useYn","mobileVisible");
        Map<String,Object> write=new HashMap<>(input);validateMenu(write);String screen=(String)write.get("screenId");
        if(screen!=null && (!screen.matches("UI-(0[1-5]|0[7-9]|[12][0-9]|3[0-3])") || "UI-22".equals(screen))) throw invalid("screenId");
        List<Map<String,Object>> tree=mapper.menus();Long parent=id(write.get("parentId"),"parentId");
        if(parent!=null) { var p=tree.stream().filter(r->Objects.equals(r.get("id"),parent)).findFirst().orElseThrow(()->invalid("parentId"));if(p.get("screenId")!=null || (!"Y".equals(p.get("useYn")) && "Y".equals(write.get("useYn")))) throw invalid("parentId"); }
        write.put("actor",user.userId());Long id=mapper.createMenu(write);audit("UI-25","MENU_CHANGE",id,"CREATE");return mapper.menus().stream().filter(m->Objects.equals(m.get("id"),id)).findFirst().orElseThrow();
    }
    @Transactional public void patchMenu(Long id,Map<String,Object> input) {
        allow("UI-25","update");administrators.lock();only(input,"name","parentId","icon","sortOrder","useYn","mobileVisible");
        List<Map<String,Object>> tree=mapper.menus();Map<String,Object> old=tree.stream().filter(m->Objects.equals(m.get("id"),id)).findFirst().orElseThrow(()->ApiException.notFound("메뉴가 없습니다."));
        Map<String,Object> write=merge(old,input);validateMenu(write);List<Map<String,Object>> proposed=tree.stream().map(m->Objects.equals(m.get("id"),id)?write:m).toList();validateTree(proposed);
        mapper.updateMenu(write);administrators.requireRecovery();audit("UI-25","MENU_CHANGE",id,"UPDATE");
    }
    @Transactional public void reorder(Map<String,Object> input) {
        allow("UI-25","update");administrators.lock();only(input,"items");if(!(input.get("items") instanceof List<?> items)) throw invalid("items");
        List<Map<String,Object>> tree=mapper.menus();Map<Long,Map<String,Object>> byId=new LinkedHashMap<>();tree.forEach(m->byId.put(((Number)m.get("id")).longValue(),new HashMap<>(m)));Set<Long> seen=new HashSet<>();
        for(Object item:items) {
            if(!(item instanceof Map<?,?> row)) throw invalid("items");Long id=id(row.get("menuId"),"menuId");
            if(!seen.add(id) || !byId.containsKey(id)) throw invalid("menuId");Map<String,Object> m=byId.get(id);m.put("parentId",id(row.get("parentId"),"parentId"));m.put("sortOrder",row.get("sortOrder"));validateMenu(m);
        }
        if(seen.size()!=tree.size()) throw invalid("items");validateTree(new ArrayList<>(byId.values()));
        byId.values().forEach(m->{m.put("actor",user.userId());mapper.updateMenu(m);});administrators.requireRecovery();audit("UI-25","MENU_CHANGE",null,"REORDER");
    }
    private void validateMenu(Map<String,Object> m) {
        text(m,"name",50,true);text(m,"icon",30,false);yn(m);
        if(!(m.get("mobileVisible") instanceof Boolean)) throw invalid("mobileVisible");
        if(!(m.get("sortOrder") instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<0) throw invalid("sortOrder");
    }
    private void validateTree(List<Map<String,Object>> tree) {
        Map<Long,Map<String,Object>> rows=new HashMap<>();tree.forEach(m->rows.put(((Number)m.get("id")).longValue(),m));
        for(var row:tree) {
            Set<Long> path=new HashSet<>();Map<String,Object> cursor=row;
            while(cursor!=null) {
                Long key=((Number)cursor.get("id")).longValue();if(!path.add(key)) throw invalid("parentId");
                Long parent=id(cursor.get("parentId"),"parentId");if(parent==null) break;var next=rows.get(parent);
                if(next==null || next.get("screenId")!=null || ("Y".equals(row.get("useYn")) && !"Y".equals(next.get("useYn")))) throw invalid("parentId");cursor=next;
            }
        }
    }
    public Map<String,Object> history(Map<String,Object> q) { allow("UI-23","read");page(q);return pageResult(mapper.history(q),mapper.historyCount(q),q); }
    public List<Map<String,Object>> exportRows(String kind,Map<String,Object> q) { allow("UI-23","download");q.remove("limit");q.remove("offset");return "users".equals(kind)?mapper.users(q):mapper.history(q); }
    private void audit(String screen,String type,Long id,String detail) {
        Map<String,Object> event=new HashMap<>();event.put("actor",user.userId());event.put("type",type);event.put("screenId",screen);event.put("target",id==null?null:id.toString());event.put("detail",detail);mapper.audit(event);
    }
    private Map<String,Object> merge(Map<String,Object> old,Map<String,Object> input) { Map<String,Object> result=new HashMap<>(old);result.putAll(input);result.put("actor",user.userId());return result; }
    private static Map<String,Object> require(Map<String,Object> row) { if(row==null) throw ApiException.notFound("항목이 없습니다.");return row; }
    private static void only(Map<String,Object> row,String... keys) { Set<String> allowed=Set.of(keys);for(String key:row.keySet()) if(!allowed.contains(key)) throw invalid(key); }
    private static void text(Map<String,Object> m,String key,int max,boolean required) { Object v=m.get(key);if(v==null && !required) return;if(!(v instanceof String s) || (required && s.isBlank()) || s.length()>max) throw invalid(key); }
    private static void email(Map<String,Object> row) { text(row,"email",100,true);if(!row.get("email").toString().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid("email"); }
    private static void yn(Map<String,Object> m) { if(!Set.of("Y","N").contains(m.get("useYn"))) throw invalid("useYn"); }
    private static Long id(Object value,String field) { if(value==null) return null;if(!(value instanceof Number n) || n.longValue()<=0 || n.doubleValue()!=n.longValue()) throw invalid(field);return n.longValue(); }
    private static List<Long> ids(Map<String,Object> m,String key,boolean required) {
        if(!(m.get(key) instanceof List<?> values)) throw invalid(key);List<Long> result=values.stream().map(v->id(v,key)).toList();
        if(result.contains(null) || new HashSet<>(result).size()!=result.size() || (required && result.isEmpty())) throw invalid(key);return result;
    }
    private static ApiException invalid(String key) { return ApiException.badRequest("입력값을 확인하세요.",key); }
    private static void page(Map<String,Object> q) {
        int page=((Number)q.getOrDefault("page",1)).intValue(),size=((Number)q.getOrDefault("size",50)).intValue();new PageRequest(page,size,null);q.put("limit",size);q.put("offset",((long)page-1)*size);q.put("page",page);q.put("size",size);
    }
    private static Map<String,Object> pageResult(List<Map<String,Object>> rows,long total,Map<String,Object> q) { return Map.of("items",rows,"total",total,"page",q.get("page"),"size",q.get("size")); }
}
