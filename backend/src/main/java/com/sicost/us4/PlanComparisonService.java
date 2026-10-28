package com.sicost.us4;

import com.sicost.common.api.ApiException;
import com.sicost.common.api.DownloadArtifact;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.us4.PlanComparisonModels.*;

@Service
public class PlanComparisonService {
    private final PlanComparisonMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public PlanComparisonService(PlanComparisonMapper mapper, ExcelExportService excel, CurrentUser user, Clock clock) {
        this.mapper=mapper; this.excel=excel; this.user=user; this.clock=clock;
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    private BigDecimal tolerance() {
        BigDecimal value=mapper.tolerance(YearMonth.from(now()).toString());
        if (value==null || value.signum()<0) throw new IllegalStateException("계획 비교 기준값을 확인하세요.");
        return value;
    }
    private List<Row> rows(Query query, String gap, BigDecimal limit) {
        if(query.year()<1 || query.year()>9999) throw ApiException.badRequest("연도를 확인하세요.","year");
        if(query.projectId()!=null && query.projectId()<1) throw ApiException.badRequest("프로젝트를 확인하세요.","projectId");
        if(gap!=null && !Set.of("SHORT","MATCH","OVER").contains(gap)) throw ApiException.badRequest("차이를 확인하세요.","gap");
        return mapper.annual(query).stream().map(s -> {
            BigDecimal diff=s.actualMm().subtract(s.planMm());
            String state=diff.abs().compareTo(limit)<=0 ? "MATCH" : diff.signum()<0 ? "SHORT" : "OVER";
            return new Row(s.projectId(),s.projectName(),s.job(),s.jobName(),s.planMm(),s.actualMm(),diff,state);
        }).filter(row -> gap==null || gap.equals(row.gap()))
          .sorted(Comparator.comparing(Row::difference).thenComparing(Row::projectName).thenComparing(Row::job).thenComparingLong(Row::projectId)).toList();
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page list(Query query, String gap, int page, int size) {
        if(page<0 || size<1 || size>100) throw ApiException.badRequest("페이지를 확인하세요.","page");
        BigDecimal limit=tolerance(); var rows=rows(query,gap,limit);
        var total=new Totals(rows.stream().map(Row::planMm).reduce(BigDecimal.ZERO,BigDecimal::add),rows.stream().map(Row::actualMm).reduce(BigDecimal.ZERO,BigDecimal::add));
        int from=(int)Math.min((long)page*size,rows.size());
        return new Page(rows.subList(from,Math.min(from+size,rows.size())),rows.size(),page,size,total,limit);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public DownloadArtifact export(Query query, String gap) {
        var rows=rows(query,gap,tolerance());
        var all=new ArrayList<>(rows);
        all.add(new Row(0,"합계","","",rows.stream().map(Row::planMm).reduce(BigDecimal.ZERO,BigDecimal::add),rows.stream().map(Row::actualMm).reduce(BigDecimal.ZERO,BigDecimal::add),BigDecimal.ZERO,""));
        List<ExportColumn<Row>> columns=List.of(
            new ExportColumn<>("projectName","프로젝트",false,false,Row::projectName),
            new ExportColumn<>("jobName","직무",false,false,Row::jobName),
            new ExportColumn<>("planMm","계획 MM",false,false,r->r.planMm().setScale(2).toPlainString()),
            new ExportColumn<>("actualMm","실제·예정 MM",false,false,r->r.actualMm().setScale(2).toPlainString()),
            new ExportColumn<>("gap","차이",false,false,r -> r.gap().isEmpty()?"":r.gap().equals("MATCH")?"맞음":(r.gap().equals("SHORT")?"부족 ":"초과 ")+r.difference().abs().setScale(2).toPlainString()));
        var time=now(); var bytes=excel.create("연도="+query.year()+", 프로젝트="+Objects.toString(query.projectId(),"전체")+", 직무="+Objects.toString(query.job(),"전체")+", 차이="+Objects.toString(gap,"전체"),user.name(),time,columns,all);
        return DownloadArtifact.workbook("계획대비투입_"+query.year()+"_"+time.format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx",bytes);
    }
}
