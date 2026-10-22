package com.sicost.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.admin.AdminModels.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private final AdminMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final ObjectMapper json;
    private final Clock clock;
    public AdminService(AdminMapper mapper, CurrentUser user, PermissionService permissions, ObjectMapper json, Clock clock) {
        this.mapper = mapper; this.user = user; this.permissions = permissions; this.json = json; this.clock = clock;
    }
    private void permission(String action) { permissions.can("UI-27." + action); }
    private static Map<String,String> columns(String... names) {
        Map<String,String> result = new LinkedHashMap<>();
        for (String name : names) result.put(name, name);
        return result;
    }
    private AdminListQuery query(PageQuery p, String group, String key, String month, String order) {
        return new AdminListQuery(group,key,month,order,p.offset(),p.size());
    }
    public PageData<CodeGroup> groups(PageQuery p, DataScope scope) {
        permission("read");
        String order=p.orderBy(columns("cd_grp_id","cd_grp_nm","cd_grp_se_cd","sort_seq","use_yn","codeCount"),"sort_seq ASC","cd_grp_id ASC").replace("codeCount", "code_count");
        return new PageData<>(mapper.groups(query(p,null,null,null,order)),mapper.groupCount(),p.page(),p.size());
    }
    public PageData<CodeRow> codes(String group, PageQuery p, DataScope scope) {
        permission("read"); requireGroup(group);
        String order=p.orderBy(columns("dtl_cd","cd_nm","sort_seq","use_yn","attr1_val","attr2_val","attr3_val","aply_bgn_dt"),"sort_seq ASC","dtl_cd ASC");
        return new PageData<>(mapper.codes(query(p,group,null,null,order)),mapper.codeCount(group),p.page(),p.size());
    }
    public PageData<ExcelMap> excelMaps(PageQuery p, DataScope scope) {
        permission("read");
        Map<String,String> allowed=columns("map_id","excel_lbl_nm","trns_cd_grp_id","trns_cd","cd_grp_nm","cd_nm","map_role_cd","rmrk_desc","use_yn");
        allowed.replaceAll((k,v)-> switch(k) { case "cd_grp_nm" -> "g.cd_grp_nm"; case "cd_nm" -> "d.cd_nm"; default -> "e."+v; });
        String order=p.orderBy(allowed,"e.excel_lbl_nm ASC","e.map_id ASC");
        return new PageData<>(mapper.excelMaps(query(p,null,null,null,order)),mapper.excelCount(),p.page(),p.size());
    }
    public PageData<CodeChange> history(String group, PageQuery p, DataScope scope) {
        permission("read"); if(group!=null) text(group,"group",1,30,"입력값을 확인하세요.");
        Map<String,String> allowed=columns("chg_at","cd_grp_id","dtl_cd","cd_chg_ty_cd","item_nm","bfr_val","aft_val","chg_user_id");
        allowed.replaceAll((k,v)->"h."+v); allowed.put("cd_grp_nm","cd_grp_nm");
        var q=query(p,group,null,null,p.orderBy(allowed,"h.chg_at DESC","h.cd_hist_id DESC"));
        return new PageData<>(mapper.history(q),mapper.historyCount(q),p.page(),p.size());
    }
    public PageData<OperatingParam> params(PageQuery p, DataScope scope) {
        permission("read"); String month=YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        var q=query(p,null,null,month,paramOrder(p,false));
        return new PageData<>(mapper.params(q),mapper.paramCount(month),p.page(),p.size());
    }
    public PageData<OperatingParam> versions(String key, PageQuery p, DataScope scope) {
        permission("read"); text(key,"key",1,50,"입력값을 확인하세요.");
        long count=mapper.versionCount(key); if(count==0) throw ApiException.notFound();
        return new PageData<>(mapper.versions(query(p,null,key,null,paramOrder(p,true))),count,p.page(),p.size());
    }
    private String paramOrder(PageQuery p, boolean versions) {
        return p.orderBy(columns("oprt_std_id","aply_bgn_ym","oprt_std_nm","num_val","txt_val","unit_nm","use_plc_desc"),versions?"aply_bgn_ym DESC":"oprt_std_id ASC",versions?"oprt_std_id ASC, aply_bgn_ym DESC":"oprt_std_id ASC");
    }
    private CodeGroup requireGroup(String group) {
        text(group,"group",1,30,"입력값을 확인하세요.");
        CodeGroup result=mapper.group(group); if(result==null) throw ApiException.notFound(); return result;
    }
    @Transactional
    public void createGroup(CodeGroupCreate row) {
        permission("create"); text(row.cd_grp_id(),"cd_grp_id",1,30,"그룹 코드를 입력하세요.");
        if(!row.cd_grp_id().matches("[A-Z_]{1,30}")) throw ApiException.invalid("cd_grp_id","영문 대문자와 _만 입력하세요.");
        text(row.cd_grp_nm(),"cd_grp_nm",1,100,"그룹명을 입력하세요.");
        optional(row.attr1_nm(),"attr1_nm",50); optional(row.attr2_nm(),"attr2_nm",50); optional(row.attr3_nm(),"attr3_nm",50);
        if(mapper.group(row.cd_grp_id())!=null) throw duplicate(false);
        mapper.insertGroup(new GroupWrite(row,user.userId()));
        history(new HistoryWrite(row.cd_grp_id(),null,"ADD",row.cd_grp_id(),null,row.cd_grp_nm(),user.userId()));
    }
    @Transactional
    public void saveCodes(String group, List<CodeSaveRow> rows) {
        permission("update");
        if(rows==null) throw ApiException.invalid("codes","전체 코드를 입력하세요.");
        for(int i=0;i<rows.size();i++) validateCode(rows.get(i),i);
        CodeGroup g=requireGroup(group);
        List<CodeRow> existing=mapper.codes(new AdminListQuery(group,null,null,"sort_seq ASC, dtl_cd ASC",0,Integer.MAX_VALUE));
        Map<String,CodeRow> old=new LinkedHashMap<>(); existing.forEach(r->old.put(r.dtl_cd(),r));
        if(g.cd_grp_se_cd().equals("SYSTEM")) {
            for(CodeSaveRow row:rows) {
                CodeRow before=old.get(row.dtl_cd());
                if(before!=null && !Objects.equals(before.use_yn(),row.use_yn())) throw ApiException.conflict("SYSTEM_CODE_LOCKED","시스템 코드는 사용 중지할 수 없습니다.");
            }
            for(CodeSaveRow row:rows) {
                CodeRow before=old.get(row.dtl_cd());
                if(before==null || !Objects.equals(before.attr1_val(),row.attr1_val()) || !Objects.equals(before.attr2_val(),row.attr2_val()) || !Objects.equals(before.attr3_val(),row.attr3_val()) || !Objects.equals(before.aply_bgn_dt(),row.aply_bgn_dt()))
                    throw ApiException.conflict("SYSTEM_CODE_LOCKED","시스템 코드는 이름과 순서만 바꿀 수 있습니다.");
            }
        }
        Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.size();i++) {
            CodeSaveRow row=rows.get(i);
            if(!old.containsKey(row.dtl_cd())) {
                permission("create");
                if(!row.dtl_cd().matches("[A-Z0-9_]{1,30}")) throw ApiException.invalid("["+i+"].dtl_cd","코드 형식을 확인하세요.");
                if(!row.use_yn().equals("Y")) throw ApiException.invalid("["+i+"].use_yn","새 코드는 사용 상태로 추가하세요.");
            }
            if(!seen.add(row.dtl_cd())) throw duplicate(false);
        }
        if(!seen.containsAll(old.keySet())) throw ApiException.invalid("codes","기존 코드를 모두 포함하세요.");
        for(CodeSaveRow row:rows) {
            CodeRow before=old.get(row.dtl_cd());
            if(before==null) {
                mapper.insertCode(new CodeWrite(group,row,Map.of(),LocalDateTime.now(clock),user.userId()));
                history(new HistoryWrite(group,row.dtl_cd(),"ADD",row.dtl_cd(),null,row.cd_nm(),user.userId()));
            } else {
                Map<String,Object> previous=codeFields(before), next=codeFields(row);
                Map<String,Object> changed=new LinkedHashMap<>();
                for(String field:next.keySet()) if(!Objects.equals(previous.get(field),next.get(field))) {
                    changed.put(field,next.get(field));
                    String type="UPDATE";
                    if(field.equals("use_yn")) { permission("delete"); type=row.use_yn().equals("N")?"DISABLE":"ENABLE"; }
                    history(new HistoryWrite(group,row.dtl_cd(),type,field,string(previous.get(field)),string(next.get(field)),user.userId()));
                }
                if(!changed.isEmpty()) mapper.updateCode(new CodeWrite(group,row,changed,LocalDateTime.now(clock),user.userId()));
            }
        }
    }
    private static Map<String,Object> codeFields(Object row) {
        Map<String,Object> values=new LinkedHashMap<>();
        if(row instanceof CodeRow r) { values.put("cd_nm",r.cd_nm()); values.put("sort_seq",r.sort_seq()); values.put("use_yn",r.use_yn()); values.put("attr1_val",r.attr1_val()); values.put("attr2_val",r.attr2_val()); values.put("attr3_val",r.attr3_val()); values.put("aply_bgn_dt",r.aply_bgn_dt()); }
        else if(row instanceof CodeSaveRow r) { values.put("cd_nm",r.cd_nm()); values.put("sort_seq",r.sort_seq()); values.put("use_yn",r.use_yn()); values.put("attr1_val",r.attr1_val()); values.put("attr2_val",r.attr2_val()); values.put("attr3_val",r.attr3_val()); values.put("aply_bgn_dt",r.aply_bgn_dt()); }
        return values;
    }
    private static void validateCode(CodeSaveRow row,int i) {
        String prefix="["+i+"].";
        if(row==null) throw ApiException.invalid("["+i+"]","코드를 입력하세요.");
        text(row.dtl_cd(),prefix+"dtl_cd",1,30,"코드를 입력하세요."); text(row.cd_nm(),prefix+"cd_nm",1,100,"이름을 입력하세요.");
        if(row.sort_seq()==null) throw ApiException.invalid(prefix+"sort_seq","순서를 입력하세요.");
        enumValue(row.use_yn(),prefix+"use_yn",Set.of("Y","N"));
        optional(row.attr1_val(),prefix+"attr1_val",100); optional(row.attr2_val(),prefix+"attr2_val",100); optional(row.attr3_val(),prefix+"attr3_val",100);
    }
    @Transactional
    public void createExcel(ExcelMapCreate row) {
        permission("create"); text(row.excel_lbl_nm(),"excel_lbl_nm",1,100,"엑셀 글자를 입력하세요.");
        text(row.trns_cd_grp_id(),"trns_cd_grp_id",1,30,"대응할 코드를 고르세요."); text(row.trns_cd(),"trns_cd",1,30,"대응할 코드를 고르세요.");
        if(!row.trns_cd().matches("[A-Z0-9_]{1,30}")) throw ApiException.invalid("trns_cd","대응할 코드를 고르세요.");
        enumValue(row.map_role_cd(),"map_role_cd",Set.of("MAIN","SUB")); optional(row.rmrk_desc(),"rmrk_desc",200);
        if(mapper.activeCode(row.trns_cd_grp_id(),row.trns_cd())==0) throw ApiException.invalid("trns_cd","대응할 코드를 고르세요.");
        if(mapper.excelDuplicate(row)>0) throw duplicate(true);
        ExcelInsert insert=new ExcelInsert(row,user.userId());
        try { mapper.insertExcel(insert); } catch(DuplicateKeyException e) { throw duplicate(true); }
        history(new HistoryWrite(row.trns_cd_grp_id(),row.trns_cd(),"ADD",insert.getMapId().toString(),null,identified("map_id",insert.getMapId().toString(),row.excel_lbl_nm()),user.userId()));
    }
    @Transactional
    public void patchExcel(String mapId, JsonNode body) {
        permission("update"); long id=id(mapId); object(body,Set.of("map_role_cd","rmrk_desc","use_yn"));
        if(body.isEmpty()) throw ApiException.invalid("body","변경할 값을 입력하세요.");
        Map<String,Object> requested=new LinkedHashMap<>();
        body.fields().forEachRemaining(entry->{
            String f=entry.getKey(); JsonNode value=entry.getValue();
            if(!value.isTextual()) throw ApiException.invalid(f,"입력값을 확인하세요.");
            if(f.equals("rmrk_desc")) optional(value.textValue(),f,200); else enumValue(value.textValue(),f,f.equals("use_yn")?Set.of("Y","N"):Set.of("MAIN","SUB"));
            requested.put(f,value.textValue());
        });
        ExcelMap old=mapper.excelMap(id); if(old==null) throw ApiException.notFound();
        Map<String,Object> before=new HashMap<>(); before.put("map_role_cd",old.map_role_cd()); before.put("rmrk_desc",old.rmrk_desc()); before.put("use_yn",old.use_yn());
        Map<String,Object> changed=new LinkedHashMap<>();
        requested.forEach((f,v)-> { if(!Objects.equals(before.get(f),v)) {
            if(f.equals("use_yn")) permission("delete"); changed.put(f,v);
            history(new HistoryWrite(old.trns_cd_grp_id(),old.trns_cd(),"UPDATE",f,identified("map_id",mapId,before.get(f)),identified("map_id",mapId,v),user.userId()));
        }});
        if(!changed.isEmpty()) mapper.patchExcel(new ExcelPatch(id,changed));
    }
    @Transactional
    public void saveParam(String key, String month, JsonNode body) {
        permission("update"); text(key,"key",1,50,"입력값을 확인하세요."); month(month); object(body,Set.of("num_val","txt_val"));
        if(body.size()!=1) throw ApiException.invalid("num_val","올바른 값을 입력하세요.");
        if(body.has("num_val") && !body.get("num_val").isNumber()) throw ApiException.invalid("num_val","올바른 값을 입력하세요.");
        if(body.has("txt_val") && !body.get("txt_val").isTextual()) throw ApiException.invalid("txt_val","올바른 값을 입력하세요.");
        OperatingParam source=mapper.source(key,month); if(source==null) throw ApiException.notFound();
        BigDecimal num=null; String text=null; String field=source.num_val()!=null?"num_val":"txt_val";
        if(!body.has(field) || (source.num_val()==null && source.txt_val()==null)) throw ApiException.invalid(field,"올바른 값을 입력하세요.");
        if(field.equals("num_val")) {
            num=body.get(field).decimalValue(); BigDecimal normalized=num.stripTrailingZeros();
            if(num.signum()<0 || normalized.scale()>4 || normalized.precision()-normalized.scale()>14 || (("%".equals(source.unit_nm())||"%p".equals(source.unit_nm())) && num.compareTo(new BigDecimal("100"))>0)) throw ApiException.invalid(field,"올바른 값을 입력하세요.");
        } else { text=body.get(field).textValue(); text(text,field,1,200,"올바른 값을 입력하세요."); }
        String closed=mapper.lastClosed(); if(closed!=null && month.compareTo(closed)<=0) throw ApiException.conflict("CLOSED_MONTH","마감된 월은 변경할 수 없습니다.");
        Object previous=field.equals("num_val")?source.num_val():source.txt_val(), next=field.equals("num_val")?num:text;
        boolean same=previous instanceof BigDecimal b && next instanceof BigDecimal n ? b.compareTo(n)==0 : Objects.equals(previous,next);
        if(source.aply_bgn_ym().equals(month) && same) return;
        if(!source.aply_bgn_ym().equals(month)) history(new HistoryWrite("PARAM",key,"UPDATE","적용 시작월",source.aply_bgn_ym(),month,user.userId()));
        else if(!same) history(new HistoryWrite("PARAM",key,"UPDATE","적용 시작월",month,month,user.userId()));
        if(!same) history(new HistoryWrite("PARAM",key,"UPDATE",field,identified("aply_bgn_ym",source.aply_bgn_ym(),decimalString(previous)),identified("aply_bgn_ym",month,decimalString(next)),user.userId()));
        mapper.saveParam(new ParamWrite(key,month,source.aply_bgn_ym(),num,text,user.userId()));
    }
    private Object decimalString(Object value) { return value instanceof BigDecimal b?b.stripTrailingZeros().toPlainString():value; }
    private String identified(String key,String id,Object value) {
        var object=json.createObjectNode(); object.put(key,id); object.set("value",json.valueToTree(value));
        return object.toString();
    }
    private void history(HistoryWrite h) {
        if((h.before()!=null && h.before().length()>500)||(h.after()!=null && h.after().length()>500)) throw ApiException.invalid(h.item(),"변경 이력에 저장할 수 있는 길이를 초과했습니다.");
        mapper.insertHistory(h);
    }
    private static String string(Object value) { return value==null?null:value.toString(); }
    static void text(String value,String field,int min,int max,String message) {
        if(value==null || value.isBlank() || value.length()<min || value.length()>max) throw ApiException.invalid(field,message);
    }
    private static void optional(String value,String field,int max) { if(value!=null && value.length()>max) throw ApiException.invalid(field,"최대 "+max+"자까지 입력하세요."); }
    private static void enumValue(String value,String field,Set<String> allowed) { if(value==null || !allowed.contains(value)) throw ApiException.invalid(field,"입력값을 확인하세요."); }
    private static ApiException duplicate(boolean excel) { return ApiException.conflict("DUPLICATE_CODE",excel?"이미 있는 대응입니다.":"이미 있는 코드입니다."); }
    private static long id(String value) {
        try { if(!value.matches("[0-9]{1,19}") || Long.parseLong(value)<1) throw new NumberFormatException(); return Long.parseLong(value); }
        catch(NumberFormatException e) { throw ApiException.invalid("mapId","입력 형식을 확인하세요."); }
    }
    private static void month(String value) {
        try { if(!value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || YearMonth.parse(value).getYear()<1) throw new DateTimeException("month"); }
        catch(DateTimeException e) { throw ApiException.invalid("month","올바른 날짜를 입력하세요."); }
    }
    private static void object(JsonNode body,Set<String> fields) {
        if(body==null || !body.isObject()) throw ApiException.invalid("body","입력값을 확인하세요.");
        body.fieldNames().forEachRemaining(f->{if(!fields.contains(f)) throw ApiException.invalid(f,"변경할 수 없는 항목입니다.");});
    }
}
