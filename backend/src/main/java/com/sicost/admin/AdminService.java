package com.sicost.admin;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.CalendarValues;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.admin.AdminModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminService {
    private final AdminMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public AdminService(AdminMapper mapper, CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper=mapper; this.user=user; this.permissions=permissions; this.clock=clock;
    }
    public PageResult<?> list(String resource, PageRequest page, String group, String key, DataScope scope) {
        permissions.can("UI-27.read");
        String month=YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        AdminQuery q=AdminQuery.of(page,resource,group,key,month);
        return switch(resource) {
            case "groups" -> new PageResult<>(mapper.groups(q),mapper.groupCount(),q.page(),q.size());
            case "codes" -> { requireGroup(group); yield new PageResult<>(mapper.codes(q),mapper.codeCount(group),q.page(),q.size()); }
            case "maps" -> new PageResult<>(mapper.maps(q),mapper.mapCount(),q.page(),q.size());
            case "history" -> new PageResult<>(mapper.history(q),mapper.historyCount(q),q.page(),q.size());
            case "versions" -> {
                long total=mapper.versionCount(key); if(total==0) throw ApiException.notFound("기준값이 없습니다.");
                yield new PageResult<>(mapper.versions(q),total,q.page(),q.size());
            }
            default -> new PageResult<>(mapper.params(q),mapper.paramCount(q),q.page(),q.size());
        };
    }
    private CodeGroup requireGroup(String group) {
        CodeGroup row=mapper.group(group); if(row==null) throw ApiException.notFound("코드 그룹이 없습니다."); return row;
    }
    @Transactional
    public void createGroup(CodeGroupCreate input) {
        permissions.can("UI-27.create");
        if(mapper.group(input.cd_grp_id())!=null) throw duplicate("이미 있는 코드입니다.","cd_grp_id");
        mapper.createGroup(new GroupWrite(input,user.userId()));
        audit(new HistoryEntry(input.cd_grp_id(),null,"ADD","ADD",null,input.cd_grp_nm(),user.userId()));
    }
    @Transactional
    public void saveCodes(String group, List<CodeRow> rows) {
        permissions.can("UI-27.update");
        CodeGroup parent=requireGroup(group);
        List<CodeRow> existing=mapper.allCodes(group);
        Map<String,CodeRow> old=new LinkedHashMap<>(); existing.forEach(r->old.put(r.dtl_cd(),r));
        Set<String> incoming=new HashSet<>();
        for(int i=0;i<rows.size();i++) {
            CodeRow r=rows.get(i);
            if(!old.containsKey(r.dtl_cd())) AdminInput.pattern(r.dtl_cd(),"[A-Z0-9_]{1,30}","["+i+"].dtl_cd");
            if(!old.containsKey(r.dtl_cd()) && !"Y".equals(r.use_yn())) throw ApiException.badRequest("새 코드는 사용 상태여야 합니다.","["+i+"].use_yn");
        }
        for(int i=0;i<rows.size();i++) if(!incoming.add(rows.get(i).dtl_cd())) throw duplicate("이미 있는 코드입니다.","["+i+"].dtl_cd");
        if(!incoming.containsAll(old.keySet())) throw ApiException.badRequest("기존 코드를 모두 포함하세요.","dtl_cd");
        boolean system="SYSTEM".equals(parent.cd_grp_se_cd());
        if(system) {
            for(CodeRow r:rows) {
                CodeRow before=old.get(r.dtl_cd());
                if(before!=null && !Objects.equals(before.use_yn(),r.use_yn())) throw ApiException.conflict("SYSTEM_CODE_LOCKED","시스템 코드는 사용 중지할 수 없습니다.","use_yn");
            }
            for(CodeRow r:rows) {
                CodeRow before=old.get(r.dtl_cd());
                if(before==null || changed(before,r).stream().anyMatch(k->!Set.of("cd_nm","sort_seq").contains(k))) throw ApiException.conflict("SYSTEM_CODE_LOCKED","시스템 코드는 이름과 순서만 바꿀 수 있습니다.","dtl_cd");
            }
        }
        for(CodeRow row:rows) {
            CodeRow before=old.get(row.dtl_cd());
            if(before==null) {
                permissions.can("UI-27.create");
                mapper.insertCode(new CodeWrite(group,row,Set.of(),user.userId(),LocalDateTime.now(clock)));
                audit(new HistoryEntry(group,row.dtl_cd(),"ADD","ADD",null,row.cd_nm(),user.userId()));
            } else {
                Set<String> changed=changed(before,row);
                if(changed.isEmpty()) continue;
                if(changed.contains("use_yn")) permissions.can("UI-27.delete");
                mapper.updateCode(new CodeWrite(group,row,changed,user.userId(),LocalDateTime.now(clock)));
                Map<String,Object> b=values(before),a=values(row);
                for(String field:changed) {
                    String type=field.equals("use_yn")?(row.use_yn().equals("N")?"DISABLE":"ENABLE"):"UPDATE";
                    audit(new HistoryEntry(group,row.dtl_cd(),type,field,string(b.get(field)),string(a.get(field)),user.userId()));
                }
            }
        }
    }
    private static Map<String,Object> values(CodeRow r) {
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("cd_nm",r.cd_nm());v.put("sort_seq",r.sort_seq());v.put("use_yn",r.use_yn());v.put("attr1_val",r.attr1_val());v.put("attr2_val",r.attr2_val());v.put("attr3_val",r.attr3_val());v.put("aply_bgn_dt",r.aply_bgn_dt()); return v;
    }
    private static Set<String> changed(CodeRow before,CodeRow after) {
        Map<String,Object> b=values(before),a=values(after);Set<String> keys=new LinkedHashSet<>();
        b.forEach((k,v)->{if(!Objects.equals(v,a.get(k))) keys.add(k);});return keys;
    }
    @Transactional
    public void createMapping(ExcelMapCreate input) {
        permissions.can("UI-27.create");
        if(!mapper.activeTarget(input)) throw ApiException.badRequest("대응할 코드를 고르세요.","trns_cd");
        if(mapper.mappingExists(input)) throw duplicate("이미 있는 대응입니다.","excel_lbl_nm");
        Long id;
        try { id=mapper.insertMapping(new MapCreateWrite(input,user.userId())); }
        catch(DuplicateKeyException ex) { throw duplicate("이미 있는 대응입니다.","excel_lbl_nm"); }
        audit(new HistoryEntry(input.trns_cd_grp_id(),input.trns_cd(),"ADD","map_id="+id+":ADD",null,input.excel_lbl_nm(),user.userId()));
    }
    @Transactional
    public void patchMapping(String id,ExcelMapPatch patch) {
        permissions.can("UI-27.update");
        if(!id.matches("[1-9][0-9]{0,18}") || new java.math.BigInteger(id).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0) throw ApiException.badRequest("대응 번호를 확인하세요.","mapId");
        ExcelMap before=mapper.mapping(id); if(before==null) throw ApiException.notFound("대응이 없습니다.");
        if(patch.supplied().contains("use_yn")) permissions.can("UI-27.delete");
        mapper.updateMapping(new MapWrite(before,patch));
        for(String field:patch.supplied()) {
            String b=switch(field){case "map_role_cd"->before.map_role_cd();case "use_yn"->before.use_yn();default->before.rmrk_desc();};
            String a=switch(field){case "map_role_cd"->patch.map_role_cd();case "use_yn"->patch.use_yn();default->patch.rmrk_desc();};
            if(!Objects.equals(b,a)) audit(new HistoryEntry(before.trns_cd_grp_id(),before.trns_cd(),field.equals("use_yn")?("N".equals(a)?"DISABLE":"ENABLE"):"UPDATE","map_id="+id+":"+field,b,a,user.userId()));
        }
    }
    @Transactional
    public void saveParam(String key,String month,ParamValueSave value) {
        permissions.can("UI-27.update");CalendarValues.month(month,"month");
        OperatingParam source=mapper.source(key,month);if(source==null) throw ApiException.notFound("기준값이 없습니다.");
        boolean numeric=source.num_val()!=null;
        if(numeric) {
            BigDecimal n=value.num_val();
            if(n==null || value.txt_val()!=null || n.signum()<0 || n.stripTrailingZeros().scale()>4 || n.precision()-n.scale()>14 || (("%".equals(source.unit_nm()) || "%p".equals(source.unit_nm())) && n.compareTo(BigDecimal.valueOf(100))>0)) throw ApiException.badRequest("올바른 값을 입력하세요.","num_val");
        } else if(value.num_val()!=null || value.txt_val()==null || value.txt_val().isBlank() || value.txt_val().length()>200) throw ApiException.badRequest("올바른 값을 입력하세요.","txt_val");
        String closed=mapper.lastClosed();
        if(closed!=null && month.compareTo(closed)<=0) throw ApiException.conflict("CLOSED_MONTH","마감된 월은 변경할 수 없습니다.","month");
        OperatingParam before=mapper.version(key,month);
        mapper.saveParam(new ParamWrite(source,month,value,user.userId()));
        audit(new HistoryEntry("PARAM",key,"UPDATE","적용 시작월",before==null?source.aply_bgn_ym():before.aply_bgn_ym(),month,user.userId()));
        Object b=numeric?(before==null?null:before.num_val()):(before==null?null:before.txt_val());
        Object a=numeric?value.num_val():value.txt_val();
        if(!Objects.equals(string(b),string(a))) audit(new HistoryEntry("PARAM",key,"UPDATE",month+":"+(numeric?"num_val":"txt_val"),string(b),string(a),user.userId()));
    }
    private void audit(HistoryEntry entry) { mapper.appendHistory(entry); }
    private static String string(Object value) { return value==null?null:value instanceof BigDecimal n?n.stripTrailingZeros().toPlainString():value.toString(); }
    private static ApiException duplicate(String message,String field) { return ApiException.conflict("DUPLICATE_CODE",message,field); }
}
