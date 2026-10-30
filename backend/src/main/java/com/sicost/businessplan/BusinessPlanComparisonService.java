package com.sicost.businessplan;

import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.PermissionService;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.businessplan.BusinessPlanModels.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class BusinessPlanComparisonService {
    private final BusinessPlanMapper mapper;
    private final BusinessPlanActualsService sources;
    private final ExcelExportService excel;
    private final PermissionService permissions;
    private final Clock clock;
    public BusinessPlanComparisonService(BusinessPlanMapper mapper,BusinessPlanActualsService sources,
            ExcelExportService excel,PermissionService permissions,Clock clock) {
        this.mapper=mapper;this.sources=sources;this.excel=excel;this.permissions=permissions;this.clock=clock;
    }
    public Comparison compare(int year,int through,Long partId) {
        permissions.can("UI-18.read");
        if(partId!=null&&mapper.parts().stream().noneMatch(p->p.id()==partId))throw ApiException.notFound("영업 파트가 없습니다.");
        var items=mapper.list(year).stream().filter(p->partId==null||Objects.equals(p.partId(),partId)).toList();
        var plans=partId==null?companyTargets(year,items):items;
        var actuals=sources.read(year,partId);
        var months=new ArrayList<ComparedMonth>();
        for(int m=1;m<=12;m++) {
            final int month=m;
            var plan=plans.stream().filter(p->p.month()==month).findFirst().orElse(null);
            var actual=actuals.stream().filter(a->a.month()==month).findFirst().orElse(null);
            months.add(new ComparedMonth(m,plan!=null,
                metric(plan==null?null:decimal(plan.target_revenue()),actual==null?null:decimal(actual.revenue())),
                metric(plan==null?null:decimal(plan.target_operating_profit()),actual==null?null:decimal(actual.operating_profit())),
                metric(plan==null?null:plan.target_utilization_rate(),actual==null?null:actual.utilization_rate())));
        }
        var selected=months.subList(0,through);
        var cumulative=new Summary(total(selected,ComparedMonth::revenue,false),
            total(selected,ComparedMonth::operating_profit,false),total(selected,ComparedMonth::utilization_rate,true));
        var cumulativeMonths=new ArrayList<ComparedMonth>();
        for(int m=1;m<=12;m++) {
            var prefix=months.subList(0,m);
            cumulativeMonths.add(new ComparedMonth(m,prefix.stream().allMatch(ComparedMonth::target_present),
                total(prefix,ComparedMonth::revenue,false),total(prefix,ComparedMonth::operating_profit,false),
                total(prefix,ComparedMonth::utilization_rate,true)));
        }
        var data=new ComparisonData(List.copyOf(months),cumulative,List.copyOf(cumulativeMonths));
        var warnings=new LinkedHashSet<String>();
        actuals.stream().filter(a->a.month()<=through).forEach(a->warnings.addAll(a.warnings()));
        if(selected.stream().anyMatch(m->!m.target_present()))warnings.add("MISSING_TARGET: 목표 미입력 월이 있습니다.");
        if(selected.stream().anyMatch(m->m.target_present()&&m.utilization_rate().target()==null))
            warnings.add("MISSING_UTILIZATION_TARGET: 전사 목표 가동률을 합산할 계획 분모가 없습니다. 파트별로 조회하세요.");
        var file=export(year,through,partId,data);
        return new Comparison(items,actuals,warnings.isEmpty()?null:String.join("\n",warnings),List.copyOf(warnings),data,file);
    }
    private static List<Plan> companyTargets(int year,List<Plan> items) {
        var result=new ArrayList<Plan>();
        for(int month=1;month<=12;month++) {
            final int selected=month;
            var rows=items.stream().filter(p->p.month()==selected).toList();
            if(rows.isEmpty())continue;
            var company=rows.stream().filter(p->p.partId()==null).findFirst();
            if(company.isPresent()){result.add(company.get());continue;}
            long revenue=0,profit=0;
            for(var row:rows){revenue=Math.addExact(revenue,row.target_revenue());profit=Math.addExact(profit,row.target_operating_profit());}
            // Part utilization rates have no additive company denominator in the plan table.
            result.add(new Plan(year,month,null,revenue,profit,null));
        }
        return List.copyOf(result);
    }
    private static BigDecimal decimal(Long v){return v==null?null:BigDecimal.valueOf(v);}
    public static Metric metric(BigDecimal target,BigDecimal actual) {
        return new Metric(target,actual,target==null||actual==null?null:actual.subtract(target),
            target==null||actual==null||target.signum()==0?null:actual.multiply(BigDecimal.valueOf(100)).divide(target,1,RoundingMode.HALF_UP));
    }
    private static Metric total(List<ComparedMonth> months,Function<ComparedMonth,Metric> value,boolean average) {
        BigDecimal target=BigDecimal.ZERO,actual=BigDecimal.ZERO;
        boolean missingTarget=false,missingActual=false;
        for(var month:months) {
            var metric=value.apply(month);
            if(metric.target()==null)missingTarget=true;
            else target=average?target.add(metric.target()):BigDecimal.valueOf(Math.addExact(target.longValueExact(),metric.target().longValueExact()));
            if(metric.actual()==null)missingActual=true;
            else actual=average?actual.add(metric.actual()):BigDecimal.valueOf(Math.addExact(actual.longValueExact(),metric.actual().longValueExact()));
        }
        // Achievement always divides cumulative actual by cumulative target, never averages percentages.
        var summed=metric(missingTarget?null:target,missingActual?null:actual);
        if(!average)return summed;
        var divisor=BigDecimal.valueOf(months.size());
        return new Metric(summed.target()==null?null:summed.target().divide(divisor,1,RoundingMode.HALF_UP),
            summed.actual()==null?null:summed.actual().divide(divisor,1,RoundingMode.HALF_UP),
            summed.difference()==null?null:summed.difference().divide(divisor,1,RoundingMode.HALF_UP),summed.achievement_rate());
    }
    private record ExportRow(String month,String metric,Metric value) {}
    private ExportFile export(int year,int through,Long partId,ComparisonData data) {
        permissions.can("UI-18.download");
        var rows=new ArrayList<ExportRow>();
        for(var month:data.months()) {
            rows.add(new ExportRow(month.month()+"월","매출",month.revenue()));
            rows.add(new ExportRow(month.month()+"월","영업이익",month.operating_profit()));
            rows.add(new ExportRow(month.month()+"월","가동률",month.utilization_rate()));
        }
        rows.add(new ExportRow("1~"+through+"월 누계","매출",data.cumulative().revenue()));
        rows.add(new ExportRow("1~"+through+"월 누계","영업이익",data.cumulative().operating_profit()));
        rows.add(new ExportRow("1~"+through+"월 누계","가동률",data.cumulative().utilization_rate()));
        List<ExportColumn<ExportRow>> columns=List.of(
            new ExportColumn<>("month","기간",false,false,ExportRow::month),
            new ExportColumn<>("metric","항목",false,false,ExportRow::metric),
            new ExportColumn<>("target","목표",true,true,r->r.value().target()),
            new ExportColumn<>("actual","실적",true,true,r->r.value().actual()),
            new ExportColumn<>("difference","차이",true,true,r->r.value().difference()),
            new ExportColumn<>("achievement","달성률(%)",false,true,r->r.value().achievement_rate()));
        byte[] bytes=excel.create(year+"년 1~"+through+"월 · 파트 "+Objects.toString(partId,"전사"),
            "기본 사용자",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),columns,rows);
        return new ExportFile("사업계획_"+year+"_"+through+".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",Base64.getEncoder().encodeToString(bytes));
    }
}
