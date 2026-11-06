package com.sicost.useraccounts;

import com.sicost.authentication.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.authentication.AuthenticationService.values;
import static com.sicost.authentication.AuthenticationService.number;

@Service
@Transactional(readOnly=true)
public class ManagementService {
    private final ManagementMapper mapper;
    private final PermissionService permissions;
    private final CurrentUser user;
    private final LastAdministratorGuard guard;
    private final AuthenticationMapper authentication;
    private final ExcelExportService excel;
    public ManagementService(ManagementMapper mapper,PermissionService permissions,CurrentUser user,LastAdministratorGuard guard,AuthenticationMapper authentication,ExcelExportService excel) {
        this.mapper=mapper;this.permissions=permissions;this.user=user;this.guard=guard;this.authentication=authentication;this.excel=excel;
    }
    private LocalDateTime now() { return LocalDateTime.now(SecurityTime.SEOUL); }
    public void require(String screen,String action) { permissions.can(screen+"."+action); }
    public static void text(Map<String,Object> input,String key,int max,boolean required) {
        Object value=input.get(key);
        if(value==null && !required) return;
        if(!(value instanceof String s) || s.length()>max || (required && s.isBlank())) throw ApiException.badRequest("입력값을 확인하세요.",key);
    }
    public static void fields(Map<String,Object> input,String... allowed) {
        if(!Set.of(allowed).containsAll(input.keySet())) throw ApiException.badRequest("변경할 수 없는 항목입니다.",null);
    }
    private Map<String,Object> write(Map<String,Object> body,Long id) {
        var result=new HashMap<>(body);result.put("id",id);result.put("actor",user.userId());result.put("now",now());return result;
    }
    public static List<Long> ids(Object input,String field,boolean required) {
        if(!(input instanceof List<?> list) || (required && list.isEmpty())) throw ApiException.badRequest("대상을 선택하세요.",field);
        var result=new ArrayList<Long>();
        for(Object value:list) { if(!(value instanceof Number n) || n.longValue()<1 || n.doubleValue()!=n.longValue() || result.contains(n.longValue())) throw ApiException.badRequest("대상을 확인하세요.",field);result.add(n.longValue()); }
        return result;
    }
    private Map<String,Object> userRow(long id) {
        var row=mapper.user(id);if(row==null) throw ApiException.notFound("계정이 없습니다.");
        row.put("roleIds",mapper.userRoles(id));row.put("partIds",mapper.userParts(id));row.put("projectIds",mapper.userProjects(id));return row;
    }
    public Map<String,Object> getUser(long id) { require("UI-23","read");return userRow(id); }
    private Map<String,Object> paging(Map<String,String> filters) {
        var q=new HashMap<String,Object>(filters);
        try {
            int page=Integer.parseInt(filters.getOrDefault("page","0")),size=Integer.parseInt(filters.getOrDefault("size","20"));
            if(page<0 || size<1 || size>100) throw new NumberFormatException();
            q.put("page",page);q.put("size",size);q.put("offset",Math.multiplyExact(page,size));
        } catch(IllegalArgumentException|ArithmeticException ex) { throw ApiException.badRequest("페이지를 확인하세요.","page"); }
        return q;
    }
    private Map<String,Object> page(List<Map<String,Object>> rows,long total,Map<String,Object> query) { return values("items",rows,"total",total,"page",query.get("page"),"size",query.get("size")); }
    public Map<String,Object> users(Map<String,String> filters) {
        require("UI-23","read");var q=paging(filters);var rows=mapper.users(q);for(var row:rows) row.put("roleIds",mapper.userRoles(number(row,"id")));return page(rows,mapper.userCount(q),q);
    }
    private void replace(long id,String kind,List<Long> ids) { mapper.deleteAssignments(values("id",id,"kind",kind));for(long value:ids) mapper.insertAssignment(values("id",id,"kind",kind,"value",value)); }
    private void invalidate(long id) { authentication.revoke(values("id",id,"now",now())); }
    private void audit(String screen,String action,Object target,String reason) { authentication.audit(values("id",user.userId(),"screenId",screen,"action",action,"target",String.valueOf(target),"detail",reason,"ip",null,"now",now())); }
    @Transactional public Map<String,Object> createUser(Map<String,Object> body) {
        require("UI-23","create");fields(body,"loginId","name","email","basePartId","partIds","projectIds","developerId","roleIds");
        text(body,"loginId",50,true);text(body,"name",50,true);email(body);
        if(!((String)body.get("loginId")).matches("[A-Za-z0-9._-]+")) throw ApiException.badRequest("아이디를 확인하세요.","loginId");
        var roles=ids(body.get("roleIds"),"roleIds",true);var parts=ids(body.get("partIds"),"partIds",false);var projects=ids(body.get("projectIds"),"projectIds",false);
        for(long role:roles) if(Boolean.TRUE.equals(roleRow(role).get("admin"))) throw ApiException.badRequest("등록 후 위험 확인을 거쳐 관리자 역할을 부여하세요.","roleIds");
        var input=write(body,null);input.put("passwordHash",new BCryptPasswordEncoder(12).encode(AuthenticationProofs.randomId()));
        long id=mapper.createUser(input);replace(id,"roles",roles);replace(id,"parts",parts);replace(id,"projects",projects);audit("UI-23","USER_CREATE",id,null);
        return values("user",userRow(id),"resetDeliveryRequested",false);
    }
    private static void email(Map<String,Object> body) {
        text(body,"email",100,true);if(!((String)body.get("email")).matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw ApiException.badRequest("이메일을 확인하세요.","email");
    }
    @Transactional public Map<String,Object> patchUser(long id,Map<String,Object> body) {
        require("UI-23","update");fields(body,"name","email","phone","basePartId","status","resignationDate");userRow(id);
        if(body.containsKey("name")) text(body,"name",50,true);if(body.containsKey("email")) email(body);text(body,"phone",30,false);
        if(body.containsKey("status") && !Set.of("ACTIVE","LOCKED","DISABLED").contains(body.get("status"))) throw ApiException.badRequest("상태를 확인하세요.","status");
        var input=write(body,id);input.put("basePartIdPresent",body.containsKey("basePartId"));input.put("resignationDatePresent",body.containsKey("resignationDate"));
        if(body.get("resignationDate")!=null) { try { if(!LocalDate.parse(body.get("resignationDate").toString()).isAfter(now().toLocalDate())) input.put("status","DISABLED"); } catch(java.time.format.DateTimeParseException ex) { throw ApiException.badRequest("퇴사일을 확인하세요.","resignationDate"); } }
        guard.protect(()->{mapper.patchUser(input);invalidate(id);audit("UI-23","USER_CHANGE",id,null);});return userRow(id);
    }
    @Transactional public Map<String,Object> userAction(long id,String action) {
        require("UI-23",action.equals("disable")?"delete":"update");var row=userRow(id);
        var input=write(Map.of(),id);
        if(action.equals("unlock")) { if(!"LOCKED".equals(row.get("status"))) throw ApiException.conflict("INVALID_STATE","잠긴 계정만 해제할 수 있습니다.","status");input.put("status","ACTIVE");input.put("unlock",true); }
        else input.put("status","DISABLED");
        guard.protect(()->{mapper.patchUser(input);invalidate(id);audit("UI-23",action.equals("unlock")?"UNLOCK":"USER_DISABLE",id,null);});return userRow(id);
    }
    @Transactional public Map<String,Object> rolesForUser(long id,Map<String,Object> body) {
        require("UI-23","update");fields(body,"roleIds","riskConfirmed");userRow(id);var assigned=ids(body.get("roleIds"),"roleIds",true);
        var old=mapper.userRoles(id);
        for(long role:assigned) if(!old.contains(role) && Boolean.TRUE.equals(roleRow(role).get("admin")) && !Boolean.TRUE.equals(body.get("riskConfirmed"))) throw ApiException.badRequest("관리자 부여 위험을 확인하세요.","riskConfirmed");
        guard.protect(()->{replace(id,"roles",assigned);invalidate(id);audit("UI-23","PERM_CHANGE",id,null);});return userRow(id);
    }
    @Transactional public Map<String,Object> assignments(long id,Map<String,Object> body) {
        require("UI-23","update");fields(body,"basePartId","partIds","projectIds","developerId");userRow(id);
        if(!body.containsKey("basePartId") || !body.containsKey("developerId")) throw ApiException.badRequest("담당 항목을 모두 전달하세요.",null);
        var parts=ids(body.get("partIds"),"partIds",false);var projects=ids(body.get("projectIds"),"projectIds",false);var input=write(body,id);input.put("basePartIdPresent",true);input.put("developerIdPresent",true);
        guard.protect(()->{mapper.patchUser(input);replace(id,"parts",parts);replace(id,"projects",projects);invalidate(id);audit("UI-23","PERM_CHANGE",id,null);});return userRow(id);
    }
    private Map<String,Object> roleRow(long id) { var row=mapper.role(id);if(row==null) throw ApiException.notFound("역할이 없습니다.");return row; }
    public Map<String,Object> role(long id) { require("UI-24","read");return roleRow(id); }
    public Map<String,Object> roles(Map<String,String> filters) { require("UI-24","read");var q=paging(filters);var rows=mapper.roles(q);int start=Math.min((int)q.get("offset"),rows.size()),end=Math.min(start+(int)q.get("size"),rows.size());return page(rows.subList(start,end),rows.size(),q); }
    private void validateRole(Map<String,Object> body) {
        text(body,"name",50,true);text(body,"description",500,false);
        if(!Set.of("ALL","PART","PROJECT","SELF").contains(Objects.toString(body.get("dataScope"),"")) || !Set.of("Y","N").contains(Objects.toString(body.get("useYn"),""))) throw ApiException.badRequest("역할 속성을 확인하세요.","dataScope");
        for(String flag:List.of("sensitiveInquiry","partStandardChange","admin")) if(!(body.get(flag) instanceof Boolean)) throw ApiException.badRequest("역할 속성을 확인하세요.",flag);
    }
    @Transactional public Map<String,Object> saveRole(Long id,Map<String,Object> body) {
        require("UI-24",id==null?"create":"update");fields(body,"name","description","useYn","dataScope","sensitiveInquiry","partStandardChange","admin","riskConfirmed");
        var input=id==null?new HashMap<String,Object>():new HashMap<>(roleRow(id));input.putAll(body);validateRole(input);
        if(id!=null && Boolean.TRUE.equals(roleRow(id).get("builtIn")) && !Objects.equals(input.get("admin"),roleRow(id).get("admin"))) throw ApiException.badRequest("기본 역할의 관리자 속성은 불변입니다.","admin");
        if(Boolean.TRUE.equals(input.get("admin")) && (id==null || !Boolean.TRUE.equals(roleRow(id).get("admin"))) && !Boolean.TRUE.equals(body.get("riskConfirmed"))) throw ApiException.badRequest("위험을 확인하세요.","riskConfirmed");
        var write=write(input,id);
        if(id==null) { long created=mapper.createRole(write);audit("UI-24","PERM_CHANGE",created,null);return roleRow(created); }
        guard.protect(()->{mapper.patchRole(write);mapper.revokeAll();audit("UI-24","PERM_CHANGE",id,null);});return roleRow(id);
    }
    @Transactional public Map<String,Object> copyRole(long id,Map<String,Object> body) {
        require("UI-24","create");fields(body,"name","description");text(body,"name",50,true);text(body,"description",500,false);
        var input=new HashMap<>(roleRow(id));input.putAll(body);for(String flag:List.of("admin","builtIn","sensitiveInquiry","partStandardChange")) input.put(flag,false);
        input.put("copyRoleId",id);var write=write(input,null);long created=mapper.createRole(write);write.put("id",created);mapper.copyPermissions(write);audit("UI-24","PERM_CHANGE",created,null);return roleRow(created);
    }
    public Map<String,Object> rolePermissions(long id) {
        require("UI-24","read");roleRow(id);var rows=mapper.permissions(id);for(var row:rows) row.put("supportedActions",List.of("inq","reg","mdfcn","del","upld","dwnld","cnfrm"));return values("roleId",id,"permissions",rows);
    }
    @Transactional public Map<String,Object> savePermissions(long id,Map<String,Object> body) {
        require("UI-24","update");fields(body,"reason","permissions");text(body,"reason",500,true);var role=roleRow(id);
        if(!(body.get("permissions") instanceof List<?> list)) throw ApiException.badRequest("권한을 확인하세요.","permissions");
        Map<Long,Map<String,Object>> menus=new HashMap<>();for(var m:mapper.menus()) menus.put(number(m,"id"),m);
        var inputs=new ArrayList<Map<String,Object>>();var seen=new HashSet<Long>();
        for(Object value:list) {
            if(!(value instanceof Map<?,?> raw)) throw ApiException.badRequest("권한을 확인하세요.","permissions");
            Map<String,Object> input=new HashMap<>();raw.forEach((k,v)->input.put(k.toString(),v));fields(input,"menuId","inq","reg","mdfcn","del","upld","dwnld","cnfrm");
            if(!(input.get("menuId") instanceof Number n) || !seen.add(n.longValue()) || !menus.containsKey(n.longValue())) throw ApiException.badRequest("메뉴를 확인하세요.","menuId");
            for(String action:List.of("inq","reg","mdfcn","del","upld","dwnld","cnfrm")) if(!(input.get(action) instanceof Boolean)) throw ApiException.badRequest("7권한을 모두 전달하세요.",action);
            boolean other=List.of("reg","mdfcn","del","upld","dwnld","cnfrm").stream().anyMatch(k->Boolean.TRUE.equals(input.get(k)));
            if(!Boolean.TRUE.equals(input.get("inq")) && other) throw ApiException.badRequest("다른 동작에는 조회 권한이 필요합니다.","inq");
            if(!Boolean.TRUE.equals(role.get("admin")) && Set.of("UI-23","UI-24","UI-25","UI-27").contains(Objects.toString(menus.get(n.longValue()).get("screenId"),"")) && (other || Boolean.TRUE.equals(input.get("inq")))) throw ApiException.badRequest("관리자 메뉴는 관리자 역할만 허용됩니다.","menuId");
            inputs.add(write(input,id));
        }
        guard.protect(()->{inputs.forEach(mapper::savePermission);mapper.revokeAll();audit("UI-24","PERM_CHANGE",id,(String)body.get("reason"));});return rolePermissions(id);
    }
    public Map<String,Object> menus(Map<String,String> filters) { require("UI-25","read");var q=paging(filters);var rows=mapper.menus().stream().filter(m->filters.getOrDefault("search","").isBlank() || m.get("name").toString().contains(filters.get("search"))).toList();int start=Math.min((int)q.get("offset"),rows.size());return page(rows.subList(start,Math.min(start+(int)q.get("size"),rows.size())),rows.size(),q); }
    private void validateTree(List<Map<String,Object>> rows) {
        Map<Long,Map<String,Object>> byId=new HashMap<>();for(var row:rows) if(byId.put(number(row,"id"),row)!=null) throw ApiException.badRequest("중복 메뉴입니다.","items");
        for(var row:rows) {
            text(row,"name",50,true);text(row,"icon",30,false);
            if(!Set.of("Y","N").contains(Objects.toString(row.get("useYn"),"")) || !(row.get("mobileVisible") instanceof Boolean) || !(row.get("sortOrder") instanceof Number n) || n.intValue()<0) throw ApiException.badRequest("메뉴 속성을 확인하세요.",null);
            var visited=new HashSet<Long>();var node=row;
            while(node!=null) {
                if(!visited.add(number(node,"id"))) throw ApiException.badRequest("메뉴 순환은 허용되지 않습니다.","parentId");
                if("Y".equals(row.get("useYn")) && !"Y".equals(node.get("useYn"))) throw ApiException.badRequest("비활성 부모 아래 활성 메뉴를 둘 수 없습니다.","parentId");
                Object parent=node.get("parentId");if(parent==null) break;
                if(!(parent instanceof Number p) || !byId.containsKey(p.longValue())) throw ApiException.badRequest("상위 메뉴를 확인하세요.","parentId");node=byId.get(p.longValue());
            }
        }
    }
    @Transactional public Map<String,Object> saveMenu(Long id,Map<String,Object> body) {
        require("UI-25",id==null?"create":"update");
        if(id==null) fields(body,"name","icon","screenId","parentId","sortOrder","useYn","mobileVisible");else fields(body,"name","icon","parentId","sortOrder","useYn","mobileVisible","impactConfirmed");
        var rows=new ArrayList<>(mapper.menus());Map<String,Object> input;
        if(id==null) { input=new HashMap<>(body);input.put("id",-1L);String screen=(String)body.get("screenId");if(screen!=null && (!screen.matches("UI-(0[1-5]|0[7-9]|[12][0-9]|3[0-3])") || Set.of("UI-22").contains(screen))) throw ApiException.badRequest("등록된 업무 화면만 연결하세요.","screenId");rows.add(input); }
        else { input=rows.stream().filter(r->number(r,"id")==id).findFirst().map(HashMap::new).orElseThrow(()->ApiException.notFound("메뉴가 없습니다."));
            if("N".equals(body.get("useYn")) && !Boolean.TRUE.equals(body.get("impactConfirmed"))) throw ApiException.badRequest("중지 영향을 확인하세요.","impactConfirmed");input.putAll(body);rows.removeIf(r->number(r,"id")==id);rows.add(input); }
        validateTree(rows);var write=write(input,id);
        if(id==null) {long created=mapper.createMenu(write);audit("UI-25","MENU_CHANGE",created,null);return mapper.menus().stream().filter(r->number(r,"id")==created).findFirst().orElseThrow();}
        guard.protect(()->{mapper.patchMenu(write);mapper.revokeAll();audit("UI-25","MENU_CHANGE",id,null);});return input;
    }
    @Transactional public void reorder(Map<String,Object> body) {
        require("UI-25","update");fields(body,"items");if(!(body.get("items") instanceof List<?> items)) throw ApiException.badRequest("정렬을 확인하세요.","items");
        var rows=mapper.menus();Map<Long,Map<String,Object>> proposed=new HashMap<>();for(var row:rows) proposed.put(number(row,"id"),new HashMap<>(row));
        var seen=new HashSet<Long>();for(Object value:items) {
            if(!(value instanceof Map<?,?> item) || !(item.get("id") instanceof Number id) || !seen.add(id.longValue()) || !proposed.containsKey(id.longValue())) throw ApiException.badRequest("메뉴 전체를 전달하세요.","items");
            if(!Set.of("id","parentId","sortOrder").containsAll(item.keySet())) throw ApiException.badRequest("정렬 항목을 확인하세요.","items");
            proposed.get(id.longValue()).put("parentId",item.get("parentId"));proposed.get(id.longValue()).put("sortOrder",item.get("sortOrder"));
        }
        if(seen.size()!=rows.size()) throw ApiException.badRequest("메뉴 전체를 전달하세요.","items");validateTree(new ArrayList<>(proposed.values()));
        guard.protect(()->{proposed.forEach((id,row)->mapper.patchMenu(write(row,id)));mapper.revokeAll();audit("UI-25","MENU_CHANGE","ORDER",null);});
    }
    public Map<String,Object> history(Map<String,String> filters) { require("UI-23","read");var q=paging(filters);return page(mapper.history(q),mapper.historyCount(q),q); }
    @Transactional public byte[] export(boolean access,Map<String,String> filters) {
        require("UI-23","download");var q=new HashMap<String,Object>(filters);q.remove("page");q.remove("size");var rows=access?mapper.history(q):mapper.users(q);
        List<String> keys=access?List.of("userId","occurredAt","action","screenId","target","ip"):List.of("loginId","name","status","lastLoginAt","resignationDate");
        List<ExcelExportService.ExportColumn<Map<String,Object>>> columns=keys.stream().map(key->new ExcelExportService.ExportColumn<Map<String,Object>>(key,key,false,false,row->row.get(key))).toList();
        audit("UI-23","DOWNLOAD",access?"ACCESS_HISTORY":"USERS",null);return excel.create("현재 검색 조건",user.name()+" / "+user.userId(),now(),columns,rows);
    }
    public Map<String,Object> channel() { require("UI-25","read");return Map.of("businessEmailEnabled",mapper.channel(YearMonth.from(now()).toString())); }
    @Transactional public Map<String,Object> channel(Map<String,Object> body) { require("UI-25","update");fields(body,"businessEmailEnabled");if(!(body.get("businessEmailEnabled") instanceof Boolean)) throw ApiException.badRequest("채널을 확인하세요.","businessEmailEnabled");var input=write(body,null);input.put("month",YearMonth.from(now()).toString());mapper.saveChannel(input);audit("UI-25","PERM_CHANGE","NOTIFICATION_CHANNEL",null);return channel(); }
}
