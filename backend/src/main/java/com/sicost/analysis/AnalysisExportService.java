package com.sicost.analysis;

import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.analysis.AnalysisModels.*;

@Service
public class AnalysisExportService {
    private final AnalysisRouter router;
    private final ExcelExportService excel;
    private final ObjectMapper json;
    private final Clock clock;
    public AnalysisExportService(AnalysisRouter router,ExcelExportService excel,ObjectMapper json,Clock clock){this.router=router;this.excel=excel;this.json=json;this.clock=clock;}
    public byte[] export(Request request) {
        Answer answer=router.execute(request);
        if(!Set.of("OK","PARTIAL","DATA_UNAVAILABLE").contains(answer.status()))throw ApiException.badRequest("재조회 조건을 확인해 주세요.","intent");
        var sheets=new ArrayList<ExportSheet<?>>();int index=1;
        for(var section:answer.table()) {
            List<JsonNode> rows=section.rows().stream().map(json::valueToTree).map(JsonNode.class::cast).toList();
            var names=new LinkedHashSet<String>();rows.forEach(r->r.fieldNames().forEachRemaining(names::add));
            var columns=new ArrayList<ExportColumn<JsonNode>>();for(String name:names)columns.add(new ExportColumn<>(name,name,false,true,r->{var v=r.get(name);return v==null||v.isNull()?null:v.isNumber()?v.decimalValue():v.isBoolean()?v.booleanValue():v.isTextual()?v.asText():v.toString();}));
            if(columns.isEmpty())columns.add(new ExportColumn<>("status","자료 상태",false,false,r->answer.status()));
            sheets.add(new ExportSheet<>("분석"+index++,columns,rows));
        }
        var refs=new ArrayList<List<String>>();
        for(var section:answer.table())refs.add(List.of(section.sourceService(),String.join(",",section.sourceScreenIds()),section.period().baseYm(),section.closedStatus(),section.unit(),section.generatedAt().toString(),String.join(" / ",section.assumptions()),String.join(" / ",section.warnings())));
        String[] labels={"원천 서비스","원천 화면","기준월","마감","단위","재조회 생성시각","가정","결손·주의"};var columns=new ArrayList<ExportColumn<List<String>>>();
        for(int i=0;i<labels.length;i++){final int n=i;columns.add(new ExportColumn<>(labels[i],labels[i],false,false,r->r.get(n)));}
        sheets.add(new ExportSheet<>("근거",columns,refs));
        return excel.createSheets("현재 원천 재조회 · 과거 답변과 다를 수 있음 · 현금 VAT 포함 / 매출·원가 VAT 제외","내부 개발 분석",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),sheets);
    }
}
