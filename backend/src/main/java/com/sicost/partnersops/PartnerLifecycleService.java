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
import static com.sicost.partnersops.PartnerLifecycleModels.*;

@Service
@Transactional(readOnly=true)
public class PartnerLifecycleService {
    private final PartnerLifecycleMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ExcelExportService excel;
    private final ObjectMapper json;
    public PartnerLifecycleService(PartnerLifecycleMapper mapper,CurrentUser user,PermissionService permissions,
                                   Clock clock,ExcelExportService excel,ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.excel=excel;this.json=json;
    }
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private PartnerDetail require(String id,boolean lock) {
        PartnerLifecycleInput.id(id,"id");
        PartnerDetail row=lock?mapper.lock(id):mapper.detail(id);
        if(row==null)throw ApiException.notFound("거래처가 없습니다.");return row;
    }
    public List<PartnerOption> options(String kind) {
        permissions.can("UI-15.read");PartnerLifecycleInput.kind(kind,true);return mapper.options(kind);
    }
    public List<PartnerAssignment> assignments(String id) {
        permissions.can("UI-15.read");require(id,false);return mapper.assignments(new PartnerDateQuery(id,today()));
    }
    public List<PartnerChange> history(String id) {
        permissions.can("UI-15.read");require(id,false);return mapper.history(id);
    }
    @Transactional
    public PartnerDisableResult disable(List<String> input) {
        permissions.can("UI-15.delete");
        if(input==null || input.isEmpty())throw ApiException.badRequest("거래처를 선택하세요.","ids");
        List<String> ids=List.copyOf(new LinkedHashSet<>(input));ids.forEach(id->PartnerLifecycleInput.id(id,"ids"));
        var rows=new HashMap<String,PartnerDetail>();
        ids.stream().sorted(Comparator.comparingLong(Long::parseLong)).forEach(id->rows.put(id,require(id,true)));
        var blocked=new ArrayList<PartnerBlock>();var allowed=new ArrayList<String>();
        for(String id:ids) {
            if(rows.get(id).use_yn().equals("N"))continue;
            var counts=mapper.block(new PartnerDateQuery(id,today()));
            if(counts.projectCount()>0 || counts.peopleCount()>0) {
                blocked.add(new PartnerBlock(id,counts.name(),counts.projectCount(),counts.peopleCount(),
                    "진행 프로젝트 "+counts.projectCount()+"건 · 소속 인력 "+counts.peopleCount()+"명. 먼저 연결을 이동해 주세요."));
            } else allowed.add(id);
        }
        if(ids.size()==1 && !blocked.isEmpty())throw ApiException.conflict("PARTNER_IN_USE",blocked.get(0).reason(),null);
        for(String id:allowed) {
            mapper.changeState(new PartnerStateWrite(id,"N",user.userId()));
            mapper.audit(new PartnerAudit(id,"DISABLE","Y","N",user.userId()));
        }
        return new PartnerDisableResult(List.copyOf(allowed),List.copyOf(blocked));
    }
    @Transactional
    public PartnerDetail enable(String id) {
        permissions.can("UI-15.delete");var before=require(id,true);
        if(before.use_yn().equals("N")) {
            mapper.changeState(new PartnerStateWrite(id,"Y",user.userId()));
            mapper.audit(new PartnerAudit(id,"UPDATE","N","Y",user.userId()));
        }
        return mapper.detail(id);
    }
    public static List<ExportColumn<Map<String,Object>>> columns(List<String> requested) {
        if(requested==null || requested.isEmpty())throw ApiException.badRequest("내려받을 열을 선택하세요.","columns");
        Map<String,String> labels=Map.ofEntries(Map.entry("ptnr_no","거래처 코드"),Map.entry("ptnr_se_cd","구분"),Map.entry("kindName","구분"),Map.entry("ptnr_nm","상호"),Map.entry("biz_reg_no","사업자번호"),Map.entry("ceo_nm","대표자"),Map.entry("indty_cd","업종"),Map.entry("industryName","업종"),Map.entry("chrg_nm","담당자"),Map.entry("chrg_tel_no","연락처"),Map.entry("txinv_email_addr","세금계산서 이메일"),Map.entry("bank_cd","은행"),Map.entry("acnt_no","계좌번호"),Map.entry("dpstr_nm","예금주"),Map.entry("projectCount","진행 프로젝트"),Map.entry("assignmentPeopleCount","소속 인력"),Map.entry("use_yn","사용 여부"));
        var keys=new LinkedHashSet<String>();
        for(String key:requested) {
            if("paymentAccount".equals(key))keys.addAll(List.of("bank_cd","acnt_no","dpstr_nm"));
            else if(labels.containsKey(key))keys.add(key);
            else throw ApiException.badRequest("내려받을 열을 확인하세요.","columns");
        }
        var result=new ArrayList<ExportColumn<Map<String,Object>>>();
        for(String key:keys)result.add(new ExportColumn<>(key,labels.get(key),Set.of("bank_cd","acnt_no","dpstr_nm").contains(key),Set.of("projectCount","assignmentPeopleCount").contains(key),row->row.get(key)));
        return List.copyOf(result);
    }
    @Transactional
    public PartnerFile export(String kind,String useYn,String q,List<String> requested,List<String> ids) {
        permissions.can("UI-15.download");var columns=columns(requested);PartnerLifecycleInput.kind(kind,false);
        q=q==null?null:q.trim();
        if(useYn!=null && !useYn.isEmpty() && !Set.of("Y","N").contains(useYn))throw ApiException.badRequest("사용 여부를 확인하세요.","useYn");
        if(ids!=null) {
            if(ids.isEmpty())throw ApiException.badRequest("거래처를 선택하세요.","ids");
            ids=ids.stream().map(id->PartnerLifecycleInput.id(id,"ids")).distinct().toList();
        }
        var query=new PartnerExportQuery(kind,useYn,q,ids,today());var rows=mapper.exportRows(query);
        if(ids!=null && rows.size()!=ids.size())throw ApiException.badRequest("선택한 거래처가 조회 조건에 없습니다.","ids");
        String filename="거래처_"+today().format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        String conditions="구분="+(kind==null?"전체":kind)+", 사용 여부="+(useYn==null||useYn.isEmpty()?"전체":useYn)+", 검색="+(q==null?"":q);
        byte[] bytes=excel.create(conditions,user.name(),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
        var detail=new LinkedHashMap<String,Object>();detail.put("kind",kind);detail.put("useYn",useYn);detail.put("q",q);detail.put("columns",requested);detail.put("ids",ids);
        try {mapper.access(new PartnerAccess(filename,json.writeValueAsString(detail),user.userId()));}
        catch(JsonProcessingException e){throw new IllegalStateException(e);}
        return new PartnerFile(filename,bytes);
    }
}
