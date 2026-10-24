package com.sicost.salespartsops;

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
import static com.sicost.salespartsops.SalesPartLifecycleModels.*;

@Service
public class SalesPartExportService {
    private final SalesPartLifecycleMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ExcelExportService excel;
    private final ObjectMapper json;
    public SalesPartExportService(SalesPartLifecycleMapper mapper,CurrentUser user,PermissionService permissions,
                                 Clock clock,ExcelExportService excel,ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.excel=excel;this.json=json;
    }
    static List<ExportColumn<Map<String,Object>>> columns(List<String> requested) {
        if(requested==null || requested.isEmpty()) throw ApiException.badRequest("내려받을 열을 선택하세요.","columns");
        var labels=Map.ofEntries(Map.entry("bsn_part_nm","파트명"),Map.entry("hq_yn","본사"),Map.entry("use_yn","사용 여부"),
            Map.entry("sort_seq","순서"),Map.entry("referenceMonth","기준월"),Map.entry("currentStandard.ldr_nm","파트장"),
            Map.entry("currentStandard.prft_dstrb_rt","이익 배분율"),Map.entry("currentStandard.bsn_fee_amt","영업비(1인·월)"),
            Map.entry("currentStandard.aply_bgn_ym","적용 시작"),Map.entry("scheduledChanges","예정된 변경"),
            Map.entry("peopleCount","소속 인력"),Map.entry("projectCount","진행 프로젝트"));
        var result=new ArrayList<ExportColumn<Map<String,Object>>>();
        for(String key:new LinkedHashSet<>(requested)) {
            if(!labels.containsKey(key)) throw ApiException.badRequest("내려받을 열을 확인하세요.","columns");
            boolean number=Set.of("sort_seq","currentStandard.prft_dstrb_rt","currentStandard.bsn_fee_amt","peopleCount","projectCount").contains(key);
            result.add(new ExportColumn<>(key,labels.get(key),false,number,row->row.get(key)));
        }
        return List.copyOf(result);
    }
    @Transactional
    public SalesPartFile export(List<String> requested,List<String> inputIds) {
        permissions.can("UI-15.download");var columns=columns(requested);
        List<Long> ids=null;
        if(inputIds!=null) {
            if(inputIds.isEmpty()) throw ApiException.badRequest("파트를 선택하세요.","ids");
            var parsed=new LinkedHashSet<Long>();
            for(String id:inputIds) {
                try {if(id==null || !id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();parsed.add(Long.parseLong(id));}
                catch(NumberFormatException e) {throw ApiException.badRequest("선택한 파트를 확인하세요.","ids");}
            }
            ids=List.copyOf(parsed);
        }
        LocalDate date=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var rows=mapper.exportRows(new SalesPartExportQuery(ids,YearMonth.from(date).toString(),date));
        if(ids!=null && rows.size()!=ids.size()) throw ApiException.badRequest("선택한 파트가 조회 조건에 없습니다.","ids");
        String filename="영업파트_"+date.format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        byte[] bytes=excel.create("전체 (중지 포함)",user.name(),LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
        var detail=new LinkedHashMap<String,Object>();detail.put("filename",filename);detail.put("conditions",Map.of());
        detail.put("columns",columns.stream().map(ExportColumn::key).toList());detail.put("ids",ids==null?null:ids.stream().map(Object::toString).toList());
        try {mapper.access(new SalesPartAccess(filename,json.writeValueAsString(detail),user.userId()));}
        catch(JsonProcessingException e) {throw new IllegalStateException(e);}
        return new SalesPartFile(filename,bytes);
    }
}
