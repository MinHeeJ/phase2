package com.sicost.systemadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.authentication.AuthenticationService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.systemadmin.AdministrationInput.*;

@Service
@Transactional(readOnly=true)
public class AdministrationService {
    private static final List<String> ACTIONS=List.of("inq","reg","mdfcn","del","upld","dwnld","cnfrm");
    private static final Set<String> USER_CREATE=Set.of("loginId","name","email","phone","partId","partIds","projectIds","developerId","roleIds","riskConfirmed");
    private static final Set<String> USER_PATCH=Set.of("name","email","phone","partId","status","resignationDate");
    private static final Set<String> ROLE=Set.of("name","description","useYn","dataScope","admin","sensitiveInquiry","partStandardChange","riskConfirmed");
    private static final Set<String> MENU=Set.of("name","icon","parentId","sortOrder","useYn","mobileVisible","riskConfirmed");
    private final AdministrationMapper mapper;
    private final AuthorizationService authorization;
    private final CurrentUser current;
    private final Clock clock;
    private final ExcelExportService excel;
    private final AuthenticationService authentication;
    public AdministrationService(AdministrationMapper mapper,AuthorizationService authorization,CurrentUser current,
                                 Clock clock,ExcelExportService excel,AuthenticationService authentication) {
        this.mapper=mapper;this.authorization=authorization;this.current=current;this.clock=clock;this.excel=excel;this.authentication=authentication;
    }
    private void require(String screen,String action) { authorization.require(screen+"."+action); }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(SessionService.SEOUL)); }
    private Map<String,Object> write(Map<String,Object> input,Long id) {
        input.put("id",id);input.put("actor",current.userId());return input;
    }
    private Map<String,Object> found(Map<String,Object> row) { if(row==null) throw ApiException.notFound("요청한 항목이 없습니다.");return new LinkedHashMap<>(row); }
    public PageResult<Map<String,Object>> users(Map<String,String> filters) {
        require("UI-23","read");var q=query(filters,false,true);
        var rows=mapper.users(q);rows.forEach(this::sets);
        return new PageResult<>(rows,mapper.userCount(q),(int)q.get("page"),(int)q.get("size"));
    }
    private void sets(Map<String,Object> row) {
        Long id=((Number)row.get("userId")).longValue();row.put("roleIds",mapper.userRoles(id));row.put("partIds",mapper.userParts(id));row.put("projectIds",mapper.userProjects(id));
    }
    public Map<String,Object> user(Long id) { require("UI-23","read");var row=found(mapper.user(id));sets(row);return row; }
    private void references(List<Long> ids,String kind) { for(Long id:ids) if(!mapper.validReference(kind,id)) throw invalid(kind+"Ids"); }
    private void optionalReference(Map<String,Object> values,String key,String kind) { if(values.get(key)!=null && !mapper.validReference(kind,((Number)values.get(key)).longValue())) throw invalid(key); }
    private void grantRisk(List<Long> ids,boolean confirmed) {
        if(ids.stream().anyMatch(id->Boolean.TRUE.equals(found(mapper.role(id)).get("admin"))) && !confirmed) throw invalid("riskConfirmed");
    }
    @Transactional public Map<String,Object> createUser(JsonNode body) {
        require("UI-23","create");mapper.lockAdministrators();
        var w=fields(body,USER_CREATE);AdministrationInput.user(w,true);
        var roles=ids(w,"roleIds",true);var parts=ids(w,"partIds",false);var projects=ids(w,"projectIds",false);
        references(roles,"role");references(parts,"part");references(projects,"project");grantRisk(roles,flag(w,"riskConfirmed"));
        optionalReference(w,"partId","part");optionalReference(w,"developerId","developer");
        w.put("passwordHash",AuthenticationProof.hash(AuthenticationProof.randomId()));
        Long id=mapper.insertUser(write(w,null));roles.forEach(v->mapper.addRole(id,v));parts.forEach(v->mapper.addPart(id,v));projects.forEach(v->mapper.addProject(id,v));
        authentication.reserveReset(id);audit("UI-23",id,"PERM_CHANGE",null);return user(id);
    }
    @Transactional public Map<String,Object> patchUser(Long id,JsonNode body) {
        require("UI-23","update");mapper.lockAdministrators();var before=found(mapper.user(id));
        var w=fields(body,USER_PATCH);AdministrationInput.user(w,false);optionalReference(w,"partId","part");
        if(w.get("resignationDate") instanceof LocalDate date && !date.isAfter(now().toLocalDate())) w.put("status","DISABLED");
        w.put("supplied",Set.copyOf(w.keySet()));mapper.updateUser(write(w,id));
        preserveAdministrator();mapper.revokeUser(id);audit("UI-23",id,"PERM_CHANGE",null);return user(id);
    }
    @Transactional public Map<String,Object> userRoles(Long id,JsonNode body) {
        require("UI-23","update");mapper.lockAdministrators();found(mapper.user(id));
        var w=fields(body,Set.of("roleIds","riskConfirmed"));var roles=ids(w,"roleIds",true);references(roles,"role");
        var existing=mapper.userRoles(id);grantRisk(roles.stream().filter(r->!existing.contains(r)).toList(),flag(w,"riskConfirmed"));
        mapper.clearRoles(id);roles.forEach(r->mapper.addRole(id,r));preserveAdministrator();mapper.revokeUser(id);
        audit("UI-23",id,"PERM_CHANGE",null);return user(id);
    }
    @Transactional public Map<String,Object> assignments(Long id,JsonNode body) {
        require("UI-23","update");found(mapper.user(id));var w=fields(body,Set.of("partIds","projectIds"));
        var parts=ids(w,"partIds",false);var projects=ids(w,"projectIds",false);references(parts,"part");references(projects,"project");
        mapper.clearParts(id);mapper.clearProjects(id);parts.forEach(v->mapper.addPart(id,v));projects.forEach(v->mapper.addProject(id,v));
        mapper.revokeUser(id);audit("UI-23",id,"PERM_CHANGE",null);return user(id);
    }
    @Transactional public Map<String,Object> disable(Long id) {
        require("UI-23","delete");mapper.lockAdministrators();found(mapper.user(id));
        var w=new HashMap<String,Object>();w.put("status","DISABLED");w.put("supplied",Set.of("status"));
        mapper.updateUser(write(w,id));preserveAdministrator();mapper.revokeUser(id);audit("UI-23",id,"PERM_CHANGE",null);return user(id);
    }
    @Transactional public Map<String,Object> unlock(Long id) {
        require("UI-23","update");found(mapper.user(id));mapper.unlock(id);mapper.revokeUser(id);audit("UI-23",id,"UNLOCK",null);return user(id);
    }
    @Transactional public void reset(Long id) { require("UI-23","update");found(mapper.user(id));authentication.reserveReset(id);mapper.revokeUser(id);audit("UI-23",id,"PW_RESET",null); }
    public List<Map<String,Object>> roles() { require("UI-24","read");return mapper.roles(); }
    public Map<String,Object> role(Long id) { require("UI-24","read");return found(mapper.role(id)); }
    public List<Map<String,Object>> permissions(Long id) { role(id);return mapper.permissions(id); }
    @Transactional public Map<String,Object> createRole(JsonNode body) {
        require("UI-24","create");var w=fields(body,ROLE);AdministrationInput.role(w);
        if(flag(w,"admin") && !flag(w,"riskConfirmed")) throw invalid("riskConfirmed");
        w.put("copyRoleId",null);Long id=mapper.insertRole(write(w,null));audit("UI-24",id,"PERM_CHANGE",null);return role(id);
    }
    @Transactional public Map<String,Object> patchRole(Long id,JsonNode body) {
        require("UI-24","update");mapper.lockAdministrators();var old=found(mapper.role(id));var delta=fields(body,ROLE);
        if(Boolean.TRUE.equals(old.get("builtin")) && delta.containsKey("admin") && !Objects.equals(old.get("admin"),delta.get("admin"))) throw invalid("admin");
        if(Boolean.TRUE.equals(old.get("builtin")) && delta.containsKey("name") && !Objects.equals(old.get("name"),delta.get("name"))) throw invalid("name");
        if(!Boolean.TRUE.equals(old.get("admin")) && Boolean.TRUE.equals(delta.get("admin")) && !flag(delta,"riskConfirmed")) throw invalid("riskConfirmed");
        old.putAll(delta);AdministrationInput.role(old);mapper.updateRole(write(old,id));preserveAdministrator();audit("UI-24",id,"PERM_CHANGE",null);return role(id);
    }
    @Transactional public Map<String,Object> copyRole(Long id,JsonNode body) {
        require("UI-24","create");var source=found(mapper.role(id));var w=fields(body,Set.of("name"));text(w,"name",50,true);
        w.put("description",source.get("description"));w.put("dataScope",source.get("dataScope"));w.put("useYn","Y");
        w.put("admin",false);w.put("sensitiveInquiry",false);w.put("partStandardChange",false);w.put("copyRoleId",id);
        Long target=mapper.insertRole(write(w,null));mapper.copyPermissions(id,target);audit("UI-24",target,"PERM_CHANGE",null);return role(target);
    }
    @Transactional public List<Map<String,Object>> savePermissions(Long id,JsonNode body) {
        require("UI-24","update");mapper.lockAdministrators();var role=found(mapper.role(id));
        if(body==null || !body.isObject() || body.size()!=2 || !body.path("reason").isTextual() || !body.path("permissions").isArray()) throw invalid("permissions");
        String reason=body.path("reason").asText();if(reason.isBlank() || reason.length()>500) throw invalid("reason");
        Map<Long,Map<String,Object>> known=new HashMap<>();mapper.menus().forEach(m->known.put(((Number)m.get("menuId")).longValue(),m));
        List<Map<String,Object>> writes=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(JsonNode n:body.path("permissions")) {
            var w=fields(n,Set.of("menuId","inq","reg","mdfcn","del","upld","dwnld","cnfrm"));Long menu=id(w.get("menuId"),"menuId");
            if(menu==null || !known.containsKey(menu) || !seen.add(menu)) throw invalid("menuId");
            for(String action:ACTIONS) yn(w,action);
            if("N".equals(w.get("inq")) && ACTIONS.stream().skip(1).anyMatch(a->"Y".equals(w.get(a)))) throw invalid("inq");
            String screen=(String)known.get(menu).get("screenId");
            if((screen==null || Set.of("UI-23","UI-24","UI-25").contains(screen) && !Boolean.TRUE.equals(role.get("admin"))) && ACTIONS.stream().anyMatch(a->"Y".equals(w.get(a)))) throw invalid("permissions");
            w.put("roleId",id);writes.add(write(w,null));
        }
        if(!seen.equals(known.keySet())) throw invalid("permissions");
        writes.forEach(mapper::savePermission);preserveAdministrator();audit("UI-24",id,"PERM_CHANGE",reason);return permissions(id);
    }
    public List<Map<String,Object>> menus() { require("UI-25","read");return mapper.menus(); }
    @Transactional public Map<String,Object> createMenu(JsonNode body) {
        require("UI-25","create");mapper.lockAdministrators();var allowed=new HashSet<>(MENU);allowed.add("screenId");var w=fields(body,allowed);AdministrationInput.menu(w);
        String screen=text(w,"screenId",10,false);
        if(screen!=null && (!screen.matches("UI-(0[1-5]|0[7-9]|1[0-9]|2[0-1]|2[3-9]|3[0-3])") || mapper.menus().stream().anyMatch(m->screen.equals(m.get("screenId"))))) throw invalid("screenId");
        var proposed=new ArrayList<>(mapper.menus());var candidate=new LinkedHashMap<>(w);candidate.put("menuId",-1L);proposed.add(candidate);validateTree(proposed);
        Long id=mapper.insertMenu(write(w,null));audit("UI-25",id,"PERM_CHANGE",null);return mapper.menus().stream().filter(m->Objects.equals(m.get("menuId"),id)).findFirst().orElseThrow();
    }
    @Transactional public Map<String,Object> patchMenu(Long id,JsonNode body) {
        require("UI-25","update");mapper.lockAdministrators();var rows=mapper.menus();var w=rows.stream().filter(m->Objects.equals(m.get("menuId"),id)).findFirst().orElseThrow(()->ApiException.notFound("메뉴가 없습니다."));
        var delta=fields(body,MENU);if("N".equals(delta.get("useYn")) && "Y".equals(w.get("useYn")) && !flag(delta,"riskConfirmed")) throw invalid("riskConfirmed");
        w.putAll(delta);AdministrationInput.menu(w);validateTree(rows);mapper.updateMenu(write(w,id));preserveAdministrator();audit("UI-25",id,"PERM_CHANGE",null);return w;
    }
    @Transactional public List<Map<String,Object>> reorder(JsonNode body) {
        require("UI-25","update");mapper.lockAdministrators();if(body==null || body.size()!=1 || !body.path("items").isArray()) throw invalid("items");
        var rows=mapper.menus();var byId=new HashMap<Long,Map<String,Object>>();rows.forEach(m->byId.put(((Number)m.get("menuId")).longValue(),m));var seen=new HashSet<Long>();
        for(JsonNode n:body.path("items")) {
            var delta=fields(n,Set.of("menuId","parentId","sortOrder"));Long id=id(delta.get("menuId"),"menuId");
            if(id==null || !seen.add(id) || !byId.containsKey(id) || !delta.containsKey("parentId") || !delta.containsKey("sortOrder")) throw invalid("items");
            byId.get(id).putAll(delta);AdministrationInput.menu(byId.get(id));
        }
        if(!seen.equals(byId.keySet())) throw invalid("items");validateTree(rows);
        rows.forEach(m->mapper.updateMenu(write(m,((Number)m.get("menuId")).longValue())));preserveAdministrator();audit("UI-25",null,"PERM_CHANGE",null);return mapper.menus();
    }
    private void validateTree(List<Map<String,Object>> rows) {
        Map<Long,Map<String,Object>> all=new HashMap<>();rows.forEach(m->all.put(((Number)m.get("menuId")).longValue(),m));
        for(var row:rows) {
            Set<Long> seen=new HashSet<>();Map<String,Object> node=row;
            while(node!=null) {
                Long id=((Number)node.get("menuId")).longValue();if(!seen.add(id)) throw invalid("parentId");
                Long parent=id(node.get("parentId"),"parentId");if(parent==null) break;
                node=all.get(parent);if(node==null || node.get("screenId")!=null) throw invalid("parentId");
                if("Y".equals(row.get("useYn")) && !"Y".equals(node.get("useYn"))) throw invalid("useYn");
            }
        }
    }
    private void preserveAdministrator() { if(mapper.readyAdministrators()<1) throw ApiException.conflict("LAST_ADMIN","로그인 가능한 관리자를 한 명 이상 유지해야 합니다.",null); }
    private void audit(String screen,Long target,String event,String reason) {
        var detail=new ObjectMapper().createObjectNode().put("result_cd","SUCCESS");if(reason!=null) detail.put("reason",reason);
        Map<String,Object> w=new HashMap<>();w.put("screen",screen);w.put("target",target==null?null:target.toString());w.put("event",event);w.put("detail",detail.toString());w.put("now",now());w.put("actor",current.userId());mapper.audit(w);
    }
    public PageResult<Map<String,Object>> history(Map<String,String> filters) {
        require("UI-23","read");var q=query(filters,true,true);return new PageResult<>(mapper.history(q),mapper.historyCount(q),(int)q.get("page"),(int)q.get("size"));
    }
    private Map<String,Object> query(Map<String,String> filters,boolean history,boolean paged) {
        Set<String> allowed=history?Set.of("userId","from","to","type","result","screenId","page","size"):Set.of("name","loginId","partId","roleId","status","page","size");
        Map<String,Object> q=new LinkedHashMap<>();
        filters.forEach((k,v)->{if(!allowed.contains(k)) throw invalid(k);if(v!=null && !v.isBlank()) q.put(k,v);});
        try {
            int page=Integer.parseInt(filters.getOrDefault("page","1")),size=Integer.parseInt(filters.getOrDefault("size","20"));
            if(page<1 || !Set.of(20,50,100).contains(size)) throw invalid("page");
            q.put("page",page);q.put("offset",Math.multiplyExact(page-1,size));if(paged) q.put("size",size);else q.remove("size");
            for(String k:List.of("userId","roleId","partId")) if(q.containsKey(k)) q.put(k,id(Long.parseLong((String)q.get(k)),k));
            for(String k:List.of("from","to")) if(q.containsKey(k)) q.put(k,LocalDate.parse((String)q.get(k)).atStartOfDay().plusDays(k.equals("to")?1:0));
        } catch(IllegalArgumentException | ArithmeticException | java.time.DateTimeException ex) { throw invalid(null); }
        return q;
    }
    @Transactional public byte[] export(Map<String,String> filters,boolean history) {
        require("UI-23","download");var q=query(filters,history,false);
        List<Map<String,Object>> rows=history?mapper.history(q):mapper.users(q);
        List<String> keys=history?List.of("createdAt","userId","name","type","result","screenId","target","ipAddress"):List.of("userId","loginId","name","status","lastLoginAt","resignationDate");
        var columns=keys.stream().map(k->new ExcelExportService.ExportColumn<Map<String,Object>>(k,k,false,false,r->r.get(k))).toList();
        byte[] result=excel.create("시스템 관리",current.name()+" ("+current.userId()+")",now(),columns,rows);audit("UI-23",null,"DOWNLOAD",null);return result;
    }
    public Map<String,Boolean> channels() { require("UI-25","read");return Map.of("businessEmailEnabled",mapper.mailEnabled()); }
    @Transactional public Map<String,Boolean> channels(JsonNode body) {
        require("UI-25","update");var w=fields(body,Set.of("businessEmailEnabled"));if(!w.containsKey("businessEmailEnabled")) throw invalid("businessEmailEnabled");
        w.put("businessEmailEnabled",flag(w,"businessEmailEnabled"));w.put("month",YearMonth.from(now()).toString());mapper.saveMailChannel(write(w,null));audit("UI-25",null,"PERM_CHANGE",null);return channels();
    }
}
