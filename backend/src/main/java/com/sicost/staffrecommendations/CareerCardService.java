package com.sicost.staffrecommendations;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CareerCardService {
    private final StaffRecommendationService recommendations;
    private final ExcelExportService excel;
    private final Clock clock;
    public CareerCardService(StaffRecommendationService recommendations,ExcelExportService excel,Clock clock) {
        this.recommendations=recommendations;this.excel=excel;this.clock=clock;
    }
    public byte[] export(HttpServletRequest request,long devId) {
        JsonNode career=recommendations.career(request,devId);List<JsonNode> rows=new ArrayList<>();career.forEach(rows::add);
        List<ExportColumn<JsonNode>> columns=List.of(
            new ExportColumn<>("project","프로젝트명",false,false,n->value(n,"projectName")),
            new ExportColumn<>("start","실제 시작일",false,false,n->value(n,"startDate")),
            new ExportColumn<>("end","실제 종료일",false,false,n->value(n,"endDate")),
            new ExportColumn<>("job","직무",false,false,n->value(n,"jobCode")),
            new ExportColumn<>("skills","구조화 기술스택",false,false,n->list(n.path("skillCodes"))),
            new ExportColumn<>("months","월별 MM",false,false,n->list(n.path("monthByMonthMm"))));
        return excel.create("내부용 실경력 카드 · 개발자 참조 "+devId+(rows.isEmpty()?" · 미기록":"")+" · 실제 배정 조회시점 기준","내부 시험 사용자",
            LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
    }
    private static String value(JsonNode n,String key){return n.hasNonNull(key)?n.path(key).asText():"미기록";}
    private static String list(JsonNode n){if(n.isEmpty())return "미기록";List<String> values=new ArrayList<>();n.forEach(v->values.add(v.isTextual()?v.asText():v.path("month").asText()+": "+v.path("mm").asText()+" MM"));return String.join(", ",values);}
}
