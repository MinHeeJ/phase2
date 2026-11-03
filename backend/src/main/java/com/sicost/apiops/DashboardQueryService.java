package com.sicost.apiops;

import com.sicost.billingsops.BillingReceivableService;
import com.sicost.businessplan.*;
import com.sicost.opportunitiesops.*;
import com.sicost.profit.MonthlyProfitService;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.apiops.DashboardModels.*;

@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class DashboardQueryService {
    private final MonthlyProfitService profit;
    private final BusinessPlanService plans;
    private final WorkforceForecastService workforce;
    private final BillingReceivableService receivables;
    private final ClosingQueryService closing;
    private final Clock clock;
    private DashboardSections sections;
    private DashboardActionSources actionSources;
    @org.springframework.beans.factory.annotation.Autowired
    public void connect(DashboardSections sections,DashboardActionSources actionSources) {
        this.sections=sections;this.actionSources=actionSources;
    }
    public DashboardQueryService(MonthlyProfitService profit,BusinessPlanService plans,WorkforceForecastService workforce,
            BillingReceivableService receivables,ClosingQueryService closing,Clock clock) {
        this.profit=profit;this.plans=plans;this.workforce=workforce;this.receivables=receivables;this.closing=closing;this.clock=clock;
    }
    private static final CardState READY=new CardState("READY",null,null);
    private static final BigDecimal THRESHOLD=new BigDecimal("0.40");
    private static CardState missing(String message) {return new CardState("MISSING",message,null);}
    private static CardState error() {return new CardState("ERROR","원천 조회에 실패했습니다.",new CardError("SOURCE_READ_FAILED","원천 조회에 실패했습니다."));}
    private static <T> T read(String section,String month,List<Warning> warnings,Supplier<T> query) {
        try {return query.get();}
        catch(RuntimeException ex) {warnings.add(new Warning(section,"SOURCE_READ_FAILED","원천 조회에 실패했습니다.",month));return null;}
    }
    private static CardState metric(Long value,boolean failed,boolean closed) {
        return failed?error():value==null?missing(closed?"마감 자료 확인 필요":"자료 없음"):READY;
    }
    private static Long number(Map<String,Object> row,String key) {Object n=row==null?null:row.get(key);return n==null?null:((Number)n).longValue();}
    private static Map<String,Object> month(List<Map<String,Object>> rows,String month) {
        return rows==null?null:rows.stream().filter(r->month.equals(r.get("month"))).findFirst().orElse(null);
    }
    static BigDecimal percent(Number value,Number base) {
        if(value==null||base==null)return null;
        var denominator=new BigDecimal(base.toString());
        return denominator.signum()==0?null:new BigDecimal(value.toString()).multiply(BigDecimal.valueOf(100)).divide(denominator,1,RoundingMode.HALF_UP);
    }
    static Comparison comparison(Long amount,Long before,boolean profit) {
        boolean reference=profit&&before!=null&&before<0;
        BigDecimal change=amount==null||before==null?null:percent(BigDecimal.valueOf(amount).subtract(BigDecimal.valueOf(before)),BigDecimal.valueOf(before).abs());
        return new Comparison(before,change,reference,reference?"참고 증감률":"전월 대비");
    }
    public DashboardResponse getDashboard(String requested) {
        var context=DashboardInput.capture(requested,clock);
        return getDashboard(context);
    }
    public DashboardResponse getDashboard(DashboardInput.Context c) {
        String basis=c.basisMonth().toString();int year=c.basisMonth().getYear(),through=c.basisMonth().getMonthValue();
        var warnings=new ArrayList<Warning>();
        var close=read("closeProgress",basis,warnings,()->closing.readDashboardClosing(basis));
        var annual=read("kpis",basis,warnings,()->profit.calculate(year,c.currentMonth()));
        var selected=month(annual,basis);
        boolean closed=close!=null?"CLOSED".equals(close.status()):selected!=null&&Boolean.TRUE.equals(selected.get("closed"));
        var beforeMonth=c.basisMonth().minusMonths(1);
        var previousAnnual=beforeMonth.getYear()==year?annual:read("kpis",beforeMonth.toString(),warnings,()->profit.calculate(beforeMonth.getYear(),c.currentMonth()));
        var before=month(previousAnnual,beforeMonth.toString());
        var planResult=read("kpis",basis,warnings,()->plans.list(year));
        var targets=planResult==null?null:BusinessPlanComparisonService.companyTargets(year,planResult.items());
        var target=targets==null?null:targets.stream().filter(p->p.month()==through).findFirst().orElse(null);
        Long sales=number(selected,"revenue"),operating=number(selected,"operating_profit");
        Long salesTarget=target==null?null:target.target_revenue();
        var revenue=new RevenueKpi(metric(sales,annual==null,closed),basis,sales,comparison(sales,number(before,"revenue"),false),salesTarget,percent(sales,salesTarget));
        var operatingProfit=new ProfitKpi(metric(operating,annual==null,closed),basis,operating,percent(operating,sales),comparison(operating,number(before,"operating_profit"),true));
        var ytd=annual==null?null:MonthlyProfitService.sum(annual,1,through);
        Long ytdAmount=number(ytd,"operating_profit");
        Long ytdTarget=targets==null?null:0L;
        for(int m=1;m<=through&&ytdTarget!=null;m++) {
            final int index=m;var t=targets.stream().filter(p->p.month()==index).findFirst().orElse(null);
            ytdTarget=t==null?null:Math.addExact(ytdTarget,t.target_operating_profit());
        }
        var closedMonths=new ArrayList<String>();var openMonths=new ArrayList<String>();
        if(annual!=null)for(var r:annual)if(r.get("month").toString().compareTo(basis)<=0)
            (Boolean.TRUE.equals(r.get("closed"))?closedMonths:openMonths).add(r.get("month").toString());
        var ytdProfit=new YtdProfitKpi(metric(ytdAmount,annual==null,closed),YearMonth.of(year,1).toString(),basis,
            ytdAmount,ytdTarget,percent(ytdAmount,ytdTarget),List.copyOf(closedMonths),List.copyOf(openMonths));
        var utilization=utilization(c,closed,warnings);
        var receipt=read("kpis",null,warnings,()->receivables.currentReceivables(c.asOf()));
        var receivable=new ReceivableKpi(receipt==null?error():READY,c.today(),receipt==null?null:receipt.amount(),
            receipt==null?null:receipt.overdueCount(),receipt==null?null:receipt.overdueAmount());
        var trend=new ArrayList<RevenueCostPoint>();
        for(int m=1;m<=12;m++) {
            String ym=YearMonth.of(year,m).toString();var r=month(annual,ym);
            final int index=m;var t=targets==null?null:targets.stream().filter(p->p.month()==index).findFirst().orElse(null);
            Long rev=m>through?null:number(r,"revenue"),cost=m>through?null:number(r,"cost");
            trend.add(new RevenueCostPoint(ym,r!=null&&Boolean.TRUE.equals(r.get("closed"))?"CLOSED":"OPEN",rev,cost,
                t==null?null:t.target_revenue(),m>through?"FUTURE":annual==null?"ERROR":rev==null||cost==null?"MISSING":"READY",
                targets==null?"ERROR":t==null?"MISSING":"READY"));
        }
        var forecast=forecast(c,warnings);
        var progress=progress(c,close,warnings);
        var projects=read("projectAlerts",null,warnings,()->sections.projects(c));
        var part=read("partSummary",basis,warnings,()->sections.parts(c));
        if(part!=null && !"READY".equals(part.card().state()))warnings.add(new Warning("partSummary",part.card().state(),part.card().message(),basis));
        var pipelineSource=read("pipeline",null,warnings,()->sections.pipelineSource(c));
        var pipeline=pipelineSource==null?new Pipeline(error(),c.today(),null,null,c.currentMonth().plusMonths(1).toString(),
            c.currentMonth().plusMonths(3).toString(),null,List.of()):sections.pipeline(c,pipelineSource);
        var cash=read("cashForecast",null,warnings,()->sections.cash(c));
        if(cash==null)cash=new CashForecast(error(),c.today(),c.currentMonth().plusMonths(1).toString(),c.currentMonth().plusMonths(3).toString(),true,
            java.util.stream.IntStream.rangeClosed(1,3).mapToObj(i->new CashPoint(c.currentMonth().plusMonths(i).toString(),null,null,null,"ERROR")).toList());
        var actionData=new HashMap<String,Integer>();
        if(projects!=null)actionData.putAll(projects.counts());
        if(pipelineSource!=null) {actionData.put("PENDING_QUOTE_APPROVAL",pipelineSource.pendingCount());actionData.put("STALE_OPPORTUNITY",pipelineSource.staleCount());}
        if("READY".equals(cash.card().state()))actionData.put("CASH_SHORTAGE",(int)cash.months().stream().filter(p->Boolean.TRUE.equals(p.isShortage())).count());
        var other=read("actions",null,warnings,()->actionSources.read(c));
        if(other!=null) {
            Integer projectLow=actionData.get("LOW_MARGIN"),staffLow=other.counts().get("LOW_MARGIN");
            actionData.putAll(other.counts());
            if(projectLow==null || staffLow==null)actionData.remove("LOW_MARGIN");
            else actionData.put("LOW_MARGIN",Math.addExact(projectLow,staffLow));
        }
        var actions=actions(c,receivable,forecast,new ActionContext(actionData,other),warnings);
        var kpis=new Kpis(revenue,operatingProfit,ytdProfit,utilization,receivable);
        for(var entry:List.of(Map.entry("매출",revenue.card()),Map.entry("영업이익",operatingProfit.card()),Map.entry("누계 영업이익",ytdProfit.card()),Map.entry("가동률",utilization.card()),Map.entry("미수금",receivable.card())))
            if(!"READY".equals(entry.getValue().state()))warnings.add(new Warning("kpis",entry.getValue().state(),entry.getKey()+": "+entry.getValue().message(),basis));
        if(salesTarget==null||ytdTarget==null)warnings.add(new Warning("kpis","MISSING_TARGET","목표 없음",basis));
        return new DashboardResponse(c.asOf(),basis,close==null?null:close.lastClosedMonth(),closed?"CLOSED":"OPEN",kpis,actions,progress,
            projects==null?new ProjectAlerts(error(),c.today(),List.of()):projects.alerts(),new RevenueCostTrend(annual==null?error():trend.stream().anyMatch(p->"MISSING".equals(p.actualState()))?missing("자료 없음"):READY,basis,false,List.copyOf(trend)),
            forecast,part==null?new PartSummary(error(),basis,"파트 이익률",List.of()):part,
            pipeline,cash,List.copyOf(warnings));
    }
    private UtilizationKpi utilization(DashboardInput.Context c,boolean closed,List<Warning> warnings) {
        String month=c.basisMonth().toString();
        if(closed)return new UtilizationKpi(missing("마감 자료 확인 필요"),month,null,null,null,null,null,null);
        var value=read("kpis",month,warnings,()->workforce.readUtilization(month,c.today()));
        if(value==null)return new UtilizationKpi(error(),month,null,null,null,null,null,null);
        BigDecimal rate=percent(value.confirmedMm(),value.availableMm()),target=BigDecimal.valueOf(90);
        return new UtilizationKpi(READY,month,value.confirmedMm(),value.availableMm(),rate,target,rate==null?null:rate.subtract(target),value.idleDeveloperCount());
    }
    private WorkforceForecast forecast(DashboardInput.Context c,List<Warning> warnings) {
        var months=java.util.stream.IntStream.rangeClosed(1,6).mapToObj(i->c.currentMonth().plusMonths(i).toString()).toList();
        var q=new OpportunityForecastModels.Query(months,null,null,"month,asc",0,Integer.MAX_VALUE,c.today());
        var source=read("workforceForecast",null,warnings,()->workforce.readSupplyDemand(q));
        var points=new ArrayList<WorkforcePoint>();
        for(String month:months) {
            if(source==null){points.add(new WorkforcePoint(month,null,null,null,null,"ERROR"));continue;}
            var rows=source.items().stream().filter(r->r.month().equals(month)).toList();
            BigDecimal available=rows.stream().map(OpportunityForecastModels.Month::supply_mm).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal required=rows.stream().map(OpportunityForecastModels.Month::demand_mm).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal shortage=required.subtract(available);
            points.add(new WorkforcePoint(month,available,required,shortage,shortage.compareTo(THRESHOLD)>0,"READY"));
        }
        return new WorkforceForecast(source==null?error():READY,c.today(),months.get(0),months.get(5),
            List.of("원천의 확정 투입·연장 확률·저장 기회 확률 기준 · 가배정은 중복 가산하지 않음"),THRESHOLD,List.copyOf(points));
    }
    private CloseProgress progress(DashboardInput.Context c,ClosingQueryModels.DashboardClosing source,List<Warning> warnings) {
        String month=c.basisMonth().toString();
        if(source==null)return new CloseProgress(error(),month,"MISSING_CLOSED_SNAPSHOT",null,9,null,List.of(),List.of());
        if(source.missingFrozenChecks())return new CloseProgress(unavailable("closeProgress",month,"마감 자료 확인 필요",warnings),month,"MISSING_CLOSED_SNAPSHOT",null,9,null,List.of(),List.of());
        var checks=source.checks().stream().map(r->new CloseCheck(r.code(),r.code(),r.status(),r.count(),r.description())).toList();
        var at=source.checks().stream().map(ClosingQueryModels.StoredCheck::checkedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new CloseProgress(READY,month,"CLOSED".equals(source.status())?"CLOSED":checks.isEmpty()?"NOT_CHECKED":"CHECKED",
            (int)checks.stream().filter(r->"OK".equals(r.status())).count(),9,at==null?null:at.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),checks,checks.stream().filter(r->!"OK".equals(r.status())).toList());
    }
    private static CardState unavailable(String section,String month,String message,List<Warning> warnings) {
        warnings.add(new Warning(section,"MISSING_SOURCE",message,month));return missing(message);
    }
    private record ActionContext(Map<String,Integer> counts,DashboardActionSources.Counts source) {}
    private Actions actions(DashboardInput.Context c,ReceivableKpi receipt,WorkforceForecast forecast,ActionContext context,List<Warning> warnings) {
        String[] types={"OVERDUE_RECEIVABLE","CASH_SHORTAGE","WORKFORCE_SHORTAGE","RISK_PROJECT","MM_EXCESS","UNCONTRACTED_ASSIGNMENT","WITHDRAWAL","UNASSIGNED_PART","OVERDUE_ISSUE","STALE_PROJECT_UPDATE","BILLING_GAP","PENDING_QUOTE_APPROVAL","UNCLOSED_PREVIOUS_MONTH","STALE_OPPORTUNITY","LOW_MARGIN","UNCONFIRMED_ADDITIONAL_CONTRACT"};
        String[] titles={"연체 미수","3개월 내 자금 부족","6개월 내 인력 부족","위험 프로젝트","MM 1.0 초과","미계약 선투입","3개월 안 철수","영업 파트 미지정","기한 지난 이슈","14일 초과 업데이트 없음","지난달 청구 누락","견적 승인 대기","미마감 지난달","장기 미갱신 기회","저마진/적자","확정 전 추가 계약"};
        var items=new ArrayList<Action>();
        for(int i=0;i<types.length;i++) {
            Integer count=context.counts().get(types[i]);
            if(i==0)count=receipt.overdueCount();
            if(i==2 && "READY".equals(forecast.card().state()))count=(int)forecast.months().stream().filter(p->Boolean.TRUE.equals(p.isShortage())).count();
            if(count!=null&&count==0)continue;
            String sourceMonth=context.source()==null?(i==4||i==5?c.currentMonth().toString():null):context.source().months().get(types[i]);
            String unit=i==1||i==2?"MONTH":i==4||i==6?"PERSON":"CASE";
            Integer without=i==6&&context.source()!=null?context.source().withoutNext():null;
            Integer recommended=i==6&&context.source()!=null?context.source().recommended():null;
            String description=count==null?"판정 불가 · 원천 자료 확인 필요":i==6?
                "다음 배정 없는 "+without+"명 중 "+recommended+"명은 추천 자리 있음":"오늘 현재 원천 기준";
            items.add(new Action(types[i],i<4?"URGENT":"CAUTION",count==null?"UNDETERMINED":"READY",titles[i],count,unit,
                description,sourceMonth,without,recommended));
        }
        boolean incomplete=items.stream().anyMatch(i->"UNDETERMINED".equals(i.state()));
        if(incomplete)warnings.add(new Warning("actions","MISSING_SOURCE","일부 확인할 일 원천은 판정 불가입니다.",null));
        return new Actions(incomplete?new CardState("PARTIAL","일부 종류 판정 불가",null):READY,c.today(),items.size(),(int)items.stream().filter(i->"URGENT".equals(i.severity())).count(),List.copyOf(items));
    }
}
