package com.sicost.dashboardops;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.excel.ExcelExportService.ExportSheet;
import com.sicost.common.security.Sensitive;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

/** Lossless presentation of the query DTO; never queries or recalculates an owner. */
@Service
public class DashboardWorkbook {
    private final ExcelExportService excel;
    public DashboardWorkbook(ExcelExportService excel) { this.excel=excel; }
    public record Value(String path, @Sensitive Object value, String type, String availability, String reason) {}
    private static final Map<String,String> SHEETS;
    static {
        var sheets=new LinkedHashMap<String,String>();
        sheets.put("kpis","KPI"); sheets.put("actions","확인할 일"); sheets.put("closeProgress","월 마감");
        sheets.put("projectAlerts","프로젝트 경보"); sheets.put("revenueCostTrend","12개월 추이");
        sheets.put("workforceForecast","6개월 인력"); sheets.put("partSummary","영업 파트");
        sheets.put("pipeline","영업 기회"); sheets.put("cashForecast","3개월 자금");
        SHEETS=Collections.unmodifiableMap(sheets);
    }
    public static List<String> sheetNames() {
        var names=new ArrayList<String>(); names.add("기준 및 생성시각"); names.addAll(SHEETS.values());
        return List.copyOf(names);
    }
    private static final List<ExportColumn<Value>> COLUMNS=List.of(
        new ExportColumn<>("path","표시 DTO 항목",false,false,Value::path),
        new ExportColumn<>("value","원 단위 값 / 비율 / 근거",true,true,Value::value),
        new ExportColumn<>("type","원본 자료형",false,false,Value::type),
        new ExportColumn<>("availability","조회 상태",false,false,Value::availability),
        new ExportColumn<>("reason","조회 불가 사유",false,false,Value::reason));

    @Sensitive
    public byte[] create(JsonNode data,String actor) {
        var asOf=OffsetDateTime.parse(data.required("asOf").asText());
        String conditions="기준월 "+data.required("basisMonth").asText()+" · 생성시각 "+asOf
            +" · 오늘 기준 예측 "+asOf.toLocalDate()+" · 금액 원 / 비율 % / 인력 MM"
            +" · 매출·원가 VAT 제외 / 자금 VAT 포함 · null=미등록·조회 불가, 0=실제 0"
            +" · OPEN 원천은 요청 사이 변경될 수 있음";
        var sheets=new ArrayList<ExportSheet<?>>();
        var basis=new ArrayList<Value>();
        data.fields().forEachRemaining(e->{
            if (!SHEETS.containsKey(e.getKey())) flatten(e.getKey(),e.getValue(),"","",basis);
        });
        sheets.add(new ExportSheet<>("기준 및 생성시각",COLUMNS,List.copyOf(basis)));
        for (var entry:SHEETS.entrySet()) {
            var rows=new ArrayList<Value>();
            JsonNode section=data.get(entry.getKey());
            if (section==null || section.isNull()) {
                rows.add(new Value(entry.getKey(),null,"null","조회 불가","필수 선행영역 자료 없음"));
            } else flatten(entry.getKey(),section,"","",rows);
            sheets.add(new ExportSheet<>(entry.getValue(),COLUMNS,List.copyOf(rows)));
        }
        return excel.createSheets(conditions,actor,asOf.toLocalDateTime(),sheets);
    }
    private static void flatten(String path,JsonNode node,String inherited,String inheritedReason,List<Value> rows) {
        String state=inherited,reason=inheritedReason;
        if (node.isObject()) {
            String own=node.path("state").asText("");
            if (own.isEmpty()) own=node.path("dataState").asText("");
            if (own.isEmpty()) own=node.path("marginState").asText("");
            if (!own.isEmpty()) state=own;
            String message=node.path("message").asText("");
            if (message.isEmpty()) message=node.path("error").path("message").asText("");
            if (!message.isEmpty()) reason=message;
            if (unavailable(state) && reason.isEmpty()) reason=state;
            var fields=node.fields();
            while(fields.hasNext()) {
                var field=fields.next();
                flatten(path+"."+field.getKey(),field.getValue(),state,reason,rows);
            }
        } else if (node.isArray()) {
            if (node.isEmpty()) rows.add(new Value(path,"[]","array",unavailable(state)?"조회 불가":"조회됨",reason));
            for (int i=0;i<node.size();i++) flatten(path+"["+i+"]",node.get(i),state,reason,rows);
        } else {
            String type=node.isNull()?"null":node.isBoolean()?"boolean":node.isNumber()?"number":"string";
            Object value=node.isNull()?null:node.isBoolean()?node.booleanValue():node.isNumber()?number(node):node.asText();
            rows.add(new Value(path,value,type,node.isNull()||unavailable(state)?"조회 불가":"조회됨",
                node.isNull() && reason.isEmpty()?"미등록(null)":reason));
        }
    }
    private static Object number(JsonNode node) {
        // Excel numbers have only 15 significant decimal digits. Keep larger won values as exact text.
        var decimal=node.decimalValue();
        return decimal.precision()>15?node.asText():decimal;
    }
    private static boolean unavailable(String state) {
        return Set.of("MISSING","ERROR","PARTIAL","UNDETERMINED","MISSING_COST","ZERO_REVENUE","FUTURE",
            "MISSING_CLOSED_SNAPSHOT").contains(state);
    }
}
