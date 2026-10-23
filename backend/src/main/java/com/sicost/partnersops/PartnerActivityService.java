package com.sicost.partnersops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.partnersops.PartnerActivityModels.*;

@Service
@Transactional(readOnly=true)
public class PartnerActivityService {
    private final PartnerActivityMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ExcelExportService excel;
    private final ObjectMapper json;
    public PartnerActivityService(PartnerActivityMapper mapper,CurrentUser user,PermissionService permissions,
                                  Clock clock,ExcelExportService excel,ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.excel=excel;this.json=json;
    }
    private Clock seoul() {return clock.withZone(ZoneId.of("Asia/Seoul"));}
    private PartnerRecord require(String id,boolean lock) {
        PartnerActivityInput.id(id);
        PartnerRecord row=lock?mapper.lockPartner(id):mapper.partner(id);
        if(row==null) throw ApiException.notFound("거래처가 없습니다.");return row;
    }
    public List<PartnerOption> options(String kind) {
        permissions.can("UI-15.read");
        if(kind==null || !Set.of("CUST","VEND").contains(kind)) throw ApiException.badRequest("구분을 확인하세요.","kind");
        return mapper.options(kind);
    }
    public List<PartnerAssignment> assignments(String id) {
        permissions.can("UI-15.read");require(id,false);
        return mapper.assignments(new PartnerDateQuery(id,LocalDate.now(seoul())));
    }
    public List<PartnerChange> history(String id) {
        permissions.can("UI-15.read");require(id,false);return mapper.history(id);
    }
    @Transactional
    public PartnerDisableResult disable(List<String> input) {
        permissions.can("UI-15.delete");
        if(input==null || input.isEmpty()) throw ApiException.badRequest("거래처를 선택하세요.","ids");
        List<String> ids=input.stream().map(PartnerActivityInput::id).distinct().toList();
        // Stable lock ordering avoids overlapping batches acquiring partner locks in opposite orders.
        Map<String,PartnerRecord> rows=new HashMap<>();
        ids.stream().sorted(Comparator.comparingLong(Long::parseLong)).forEach(id->rows.put(id,require(id,true)));
        List<PartnerBlocked> blocked=new ArrayList<>();List<String> allowed=new ArrayList<>();
        for(String id:ids) {
            PartnerRecord row=rows.get(id);if("N".equals(row.use_yn())) continue;
            PartnerCounts counts=mapper.counts(new PartnerDateQuery(id,LocalDate.now(seoul())));
            if(counts.projectCount()>0 || counts.peopleCount()>0) {
                String reason="진행 프로젝트 "+counts.projectCount()+"건 / 소속 인력 "+counts.peopleCount()+"명 — 거래가 남아 있어 중지할 수 없습니다.";
                blocked.add(new PartnerBlocked(id,row.ptnr_nm(),counts.projectCount(),counts.peopleCount(),reason));
            } else allowed.add(id);
        }
        if(ids.size()==1 && !blocked.isEmpty()) throw ApiException.conflict("PARTNER_IN_USE",blocked.get(0).reason(),null);
        for(String id:allowed) {
            mapper.state(new PartnerStateWrite(id,"N",user.userId()));
            mapper.appendHistory(new PartnerHistoryWrite(id,"DISABLE","Y","N",user.userId()));
        }
        return new PartnerDisableResult(List.copyOf(allowed),List.copyOf(blocked));
    }
    @Transactional
    public Map<String,Object> enable(String id) {
        permissions.can("UI-15.delete");PartnerRecord row=require(id,true);
        if("N".equals(row.use_yn())) {
            mapper.state(new PartnerStateWrite(id,"Y",user.userId()));
            mapper.appendHistory(new PartnerHistoryWrite(id,"UPDATE","N","Y",user.userId()));
            row=require(id,false);
        }
        return row.detail();
    }
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("ptnr_se_cd","구분"),Map.entry("ptnr_no","거래처 코드"),Map.entry("ptnr_nm","상호"),
        Map.entry("biz_reg_no","사업자번호"),Map.entry("ceo_nm","대표자"),Map.entry("indty_cd","업종"),
        Map.entry("chrg_nm","담당자"),Map.entry("chrg_tel_no","연락처"),Map.entry("txinv_email_addr","세금계산서 이메일"),
        Map.entry("paymentAccount","지급 계좌"),Map.entry("projectCount","진행 프로젝트"),
        Map.entry("assignmentPeopleCount","투입 인원"),Map.entry("use_yn","사용 여부"));
    private static boolean sensitive(String key) {
        return Arrays.stream(PartnerRecord.class.getRecordComponents()).anyMatch(c->c.getName().equals(key) && c.isAnnotationPresent(Sensitive.class));
    }
    private static List<ExportColumn> columns(List<String> keys) {
        if(keys==null || keys.isEmpty() || new HashSet<>(keys).size()!=keys.size() || keys.stream().anyMatch(k->!LABELS.containsKey(k)))
            throw ApiException.badRequest("내려받을 열을 확인하세요.","columns");
        List<ExportColumn> result=new ArrayList<>();
        for(String key:keys) {
            if(key.equals("paymentAccount")) {
                result.add(new ExportColumn("bank_cd","은행",false,sensitive("bank_cd")));
                result.add(new ExportColumn("acnt_no","계좌번호",false,sensitive("acnt_no")));
                result.add(new ExportColumn("dpstr_nm","예금주",false,sensitive("dpstr_nm")));
            } else result.add(new ExportColumn(key,LABELS.get(key),Set.of("projectCount","assignmentPeopleCount").contains(key),false));
        }
        return result;
    }
    @Transactional
    public PartnerDownload export(String kind,String useYn,String q,List<String> keys,List<String> ids) {
        permissions.can("UI-15.download");List<ExportColumn> columns=columns(keys);
        if(kind!=null && !kind.isEmpty() && !Set.of("CUST","VEND","BOTH").contains(kind)) throw ApiException.badRequest("구분을 확인하세요.","kind");
        if(useYn!=null && !useYn.isEmpty() && !Set.of("Y","N").contains(useYn)) throw ApiException.badRequest("사용 여부를 확인하세요.","useYn");
        q=q==null?null:q.trim();
        var query=new PartnerExportQuery(kind,useYn,q,LocalDate.now(seoul()));
        List<Map<String,Object>> rows=mapper.exportRows(query);
        if(ids!=null) {
            if(ids.isEmpty()) throw ApiException.badRequest("선택 범위를 확인하세요.","ids");
            Set<String> selected=new HashSet<>();ids.forEach(id->selected.add(PartnerActivityInput.id(id)));
            Set<String> matching=new HashSet<>();rows.forEach(r->matching.add(r.get("ptnr_id").toString()));
            if(!matching.containsAll(selected)) throw ApiException.badRequest("조회 조건에 없는 거래처입니다.","ids");
            rows=rows.stream().filter(r->selected.contains(r.get("ptnr_id").toString())).toList();
        }
        Map<String,Object> filters=new LinkedHashMap<>();filters.put("kind",kind);filters.put("useYn",useYn);filters.put("q",q);
        String conditions=String.format("구분=%s, 사용 여부=%s, 검색=%s",kind==null?"전체":kind,useYn==null||useYn.isEmpty()?"전체":useYn,q==null?"":q);
        byte[] bytes=excel.write(columns,rows,conditions,user.name(),LocalDateTime.now(seoul()));
        String filename="거래처_"+LocalDate.now(seoul()).format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        Map<String,Object> audit=new LinkedHashMap<>();audit.put("filters",filters);audit.put("columns",keys);audit.put("ids",ids);audit.put("rowCount",rows.size());
        try {mapper.download(new PartnerDownloadWrite(filename,json.writeValueAsString(audit),user.userId()));}
        catch(JsonProcessingException ex) {throw new IllegalStateException("다운로드 기록 생성 실패",ex);}
        return new PartnerDownload(filename,bytes);
    }
}
