package com.sicost.partnersops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
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
    public PartnerLifecycleService(PartnerLifecycleMapper mapper,CurrentUser user,PermissionService permissions,Clock clock,ExcelExportService excel,ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.excel=excel;this.json=json;
    }
    public LocalDate today() {return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private PartnerState require(long id,boolean lock) {
        PartnerState row=lock?mapper.lock(id):mapper.state(id);
        if(row==null)throw ApiException.notFound("거래처가 없습니다.");return row;
    }
    public List<PartnerOption> listPartnerOptions(String kind) {
        permissions.can("UI-15.read");
        if(kind==null || !Set.of("CUST","VEND").contains(kind))throw PartnerExportQuery.invalid("kind");
        return mapper.options(kind);
    }
    public List<PartnerAssignment> listPartnerAssignments(String id) {
        permissions.can("UI-15.read");long key=PartnerExportQuery.id(id,"id");require(key,false);
        return mapper.assignments(new PartnerTarget(key,today()));
    }
    public List<PartnerChange> listPartnerHistory(String id) {
        permissions.can("UI-15.read");long key=PartnerExportQuery.id(id,"id");require(key,false);
        return mapper.history(key).stream().map(h->new PartnerChange(h.changedAt(),h.column(),h.itemName(),"acnt_no".equals(h.column())?lastFour(h.before()):h.before(),"acnt_no".equals(h.column())?lastFour(h.after()):h.after(),h.changeType(),h.actorName())).toList();
    }
    private static String lastFour(String value) {if(value==null)return null;String digits=value.replaceAll("[^0-9]","");return digits.substring(Math.max(0,digits.length()-4));}
    @Transactional
    public PartnerDisableResult savePartnerDisable(List<Long> ids) {
        permissions.can("UI-15.delete");
        if(ids==null || ids.isEmpty())throw PartnerExportQuery.invalid("ids");
        List<Long> unique=ids.stream().distinct().sorted().toList();
        List<PartnerState> rows=unique.stream().map(id->require(id,true)).toList();
        List<PartnerDisableBlocked> blocked=new ArrayList<>();List<String> disabled=new ArrayList<>();
        for(PartnerState row:rows) {
            if("N".equals(row.useYn()))continue;
            PartnerCounts counts=mapper.counts(new PartnerTarget(row.id(),today()));
            if(counts.projectCount()>0 || counts.peopleCount()>0) {
                String reason="진행 프로젝트 "+counts.projectCount()+"건 / 소속 인력 "+counts.peopleCount()+"명. 거래가 남아 있어 중지할 수 없습니다.";
                blocked.add(new PartnerDisableBlocked(row.id().toString(),row.name(),counts.projectCount(),counts.peopleCount(),reason));
            } else disabled.add(row.id().toString());
        }
        if(unique.size()==1 && !blocked.isEmpty())throw ApiException.conflict("PARTNER_IN_USE",blocked.get(0).reason(),null);
        for(String id:disabled) {
            long key=Long.parseLong(id);mapper.status(new PartnerStatusWrite(key,"N",user.userId()));
            mapper.appendHistory(new PartnerHistoryWrite(key,"DISABLE","Y","N",user.userId()));
        }
        return new PartnerDisableResult(disabled,blocked);
    }
    @Transactional
    public Map<String,Object> savePartnerEnable(String id) {
        permissions.can("UI-15.delete");long key=PartnerExportQuery.id(id,"id");PartnerState row=require(key,true);
        if("N".equals(row.useYn())) {
            mapper.status(new PartnerStatusWrite(key,"Y",user.userId()));mapper.appendHistory(new PartnerHistoryWrite(key,"UPDATE","N","Y",user.userId()));
        }
        Map<String,Object> detail=new LinkedHashMap<>(mapper.detail(key));
        if(row.kind().equals("CUST")){detail.remove("bank_cd");detail.remove("acnt_no");detail.remove("dpstr_nm");}
        if(row.kind().equals("VEND"))detail.remove("txinv_email_addr");
        return detail;
    }
    @Transactional
    public PartnerFile getPartnerExport(PartnerExportQuery query) {
        permissions.can("UI-15.download");
        if(query.ids()!=null)for(Long id:query.ids())require(id,false);
        List<Map<String,Object>> rows=mapper.exportRows(query);
        List<ExcelExportService.Column> columns=new ArrayList<>();
        for(String key:query.columns()) {
            if(key.equals("paymentAccount")) {
                columns.add(ExcelExportService.Column.marked("bank_cd","은행",PartnerPayment.class));
                columns.add(ExcelExportService.Column.marked("acnt_no","계좌번호",PartnerPayment.class));
                columns.add(ExcelExportService.Column.marked("dpstr_nm","예금주",PartnerPayment.class));
            } else columns.add(new ExcelExportService.Column(key,PartnerExportQuery.LABELS.get(key),Set.of("projectCount","assignmentPeopleCount").contains(key),false));
        }
        LocalDateTime now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String filename="거래처_"+now.format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        Map<String,Object> conditions=new LinkedHashMap<>();conditions.put("kind",query.kind());conditions.put("useYn",query.useYn());conditions.put("q",query.q());conditions.put("ids",query.ids());conditions.put("columns",query.columns());conditions.put("rowCount",rows.size());
        String detail;
        try {detail=json.writeValueAsString(conditions);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}
        byte[] bytes=excel.workbook(columns,rows,detail,user.name(),now);
        mapper.appendAccess(new PartnerAccessWrite(filename,detail,user.userId()));
        return new PartnerFile(filename,bytes);
    }
}
