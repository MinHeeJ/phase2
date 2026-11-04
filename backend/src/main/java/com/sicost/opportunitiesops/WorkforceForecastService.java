package com.sicost.opportunitiesops;

import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.common.workforce.CandidateForecastReader;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.opportunitiesops.OpportunityForecastModels.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class WorkforceForecastService implements CandidateForecastReader {
    private final OpportunityForecastMapper mapper;
    private final MonthlyProfitReader profit;
    private final Clock clock;
    public WorkforceForecastService(OpportunityForecastMapper mapper,MonthlyProfitReader profit,Clock clock){this.mapper=mapper;this.profit=profit;this.clock=clock;}
    private static BigDecimal zero(){return BigDecimal.ZERO;}
    private static BigDecimal positive(BigDecimal n){return n.max(zero());}
    private static String grade(String s){return s==null?"":s;}
    private static boolean matches(Query q,String job,String grade){return(q.job()==null||q.job().equals(job))&&(q.grade()==null||q.grade().equals(grade));}
    private record Bucket(String job,String grade) {}
    public WorkforcePage forecast(Query q){
        return forecast(q,true);
    }
    public WorkforcePage readSupplyDemand(Query q){
        return forecast(q,false);
    }
    public UtilizationRead readUtilization(String month,LocalDate today){
        var q=new Query(List.of(month),null,null,"month,asc",0,Integer.MAX_VALUE,today);
        var rows=readSupplyDemand(q).items();
        BigDecimal confirmed=rows.stream().map(OpportunityForecastModels.Month::confirmed_mm).reduce(zero(),BigDecimal::add);
        BigDecimal available=rows.stream().map(OpportunityForecastModels.Month::available_mm).reduce(zero(),BigDecimal::add);
        var developers=mapper.developers(month);var leaves=mapper.leaves();
        var assignments=mapper.assignments();var actual=mapper.assignmentMonths();
        var selected=YearMonth.parse(month);
        long idle=developers.stream().filter(d->d.hire()==null||!d.hire().isAfter(selected.atEndOfMonth()))
            .filter(d->d.resign()==null||d.resign().isAfter(selected.atEndOfMonth()))
            .filter(d->leaves.stream().noneMatch(l->l.developer()==d.id()&&!l.start().isAfter(selected.atEndOfMonth())
                &&(l.end()==null||!l.end().isBefore(selected.atDay(1)))))
            .filter(d->actual.stream().filter(a->a.month().equals(month)&&assignments.stream()
                .anyMatch(x->x.id()==a.assignment()&&x.developer()==d.id())).map(AssignedMonth::mm)
                .reduce(zero(),BigDecimal::add).signum()==0).map(Developer::id).distinct().count();
        return new UtilizationRead(confirmed,available,Math.toIntExact(idle));
    }
    private WorkforcePage forecast(Query q,boolean includeFinancialBaseline){
        return forecast(q,includeFinancialBaseline,false,Map.of(),Map.of());
    }
    public WorkforcePage readNonSensitive(Query q,Map<Long,BigDecimal> wins,Map<Long,BigDecimal> extensions) {
        return forecast(q,false,true,wins,extensions);
    }
    private WorkforcePage forecast(Query q,boolean includeFinancialBaseline,boolean nonSensitive,
            Map<Long,BigDecimal> wins,Map<Long,BigDecimal> extensions){
        if(q.job()!=null&&!mapper.code(new CandidateFilter("JOB",q.job(),null)))throw OpportunityLifecycleInput.invalid("job");
        if(q.grade()!=null&&!mapper.code(new CandidateFilter("GRADE",q.grade(),null)))throw OpportunityLifecycleInput.invalid("grade");
        var developers=nonSensitive?mapper.nonSensitiveDevelopers():mapper.developers(YearMonth.from(q.today()).toString());var leaves=mapper.leaves();
        var assignments=mapper.assignments();var actual=mapper.assignmentMonths();var plans=mapper.plans();var needs=mapper.needs();var temporary=mapper.temporary();
        Set<Bucket> buckets=new TreeSet<>(Comparator.comparing(Bucket::job).thenComparing(Bucket::grade));
        developers.forEach(d->buckets.add(new Bucket(grade(d.job()),grade(d.grade()))));
        plans.forEach(p->buckets.add(new Bucket(p.job(),grade(p.grade()))));needs.forEach(n->buckets.add(new Bucket(n.job(),grade(n.grade()))));
        assignments.forEach(a->buckets.add(new Bucket(a.job(),grade(a.grade()))));
        List<OpportunityForecastModels.Month> rows=new ArrayList<>();
        for(String month:q.months())for(var b:buckets){
            if(!matches(q,b.job(),b.grade()))continue;YearMonth m=YearMonth.parse(month);
            BigDecimal supply=zero(),available=zero(),confirmed=zero(),extension=zero(),idleCost=zero(),labor=zero();int laborCount=0;
            Set<String> warnings=new LinkedHashSet<>();
            for(var d:developers){
                if(!Objects.equals(d.job(),b.job())||!grade(d.grade()).equals(b.grade()))continue;
                long days=0;for(var day=m.atDay(1);!day.isAfter(m.atEndOfMonth());day=day.plusDays(1)){
                    final LocalDate date=day;
                    if(d.hire()!=null&&date.isBefore(d.hire())||d.resign()!=null&&!date.isBefore(d.resign()))continue;
                    boolean absent=leaves.stream().anyMatch(l->l.developer()==d.id()&&!date.isBefore(l.start())&&(l.end()==null||!date.isAfter(l.end())));
                    if(!absent)days++;
                }
                BigDecimal possible=BigDecimal.valueOf(days).divide(BigDecimal.valueOf(m.lengthOfMonth()),12,RoundingMode.HALF_UP);
                var own=assignments.stream().filter(a->a.developer()==d.id()).toList();
                BigDecimal used=actual.stream().filter(a->month.equals(a.month())&&own.stream().anyMatch(x->x.id()==a.assignment())).map(AssignedMonth::mm).reduce(zero(),BigDecimal::add);
                BigDecimal expected=zero();
                for(var a:own){
                    if(!YearMonth.from(a.end()).isBefore(m))continue;
                    boolean next=own.stream().anyMatch(n->n.id()!=a.id()&&n.start().isAfter(a.end())&&!YearMonth.from(n.start()).isAfter(m));if(next)continue;
                    BigDecimal factor="H".equals(a.extension())?new BigDecimal("0.80"):"M".equals(a.extension())?new BigDecimal("0.50"):zero();
                    if(extensions.containsKey(a.id()))factor=extensions.get(a.id()).movePointLeft(2);
                    BigDecimal last=actual.stream().filter(x->x.assignment()==a.id()&&x.month().equals(YearMonth.from(a.end()).toString())).map(AssignedMonth::mm).reduce(zero(),BigDecimal::add);
                    expected=expected.add(last.multiply(factor));
                }
                BigDecimal free=positive(possible.subtract(used).subtract(expected));supply=supply.add(free);available=available.add(possible);confirmed=confirmed.add(used);extension=extension.add(expected);
                if(used.compareTo(BigDecimal.ONE)>0)warnings.add("MM 1.0 초과");
                if(Set.of("FT","CT").contains(d.employee())&&possible.signum()>0){
                    if(d.labor()==null)warnings.add("원가 미확인");else {labor=labor.add(BigDecimal.valueOf(d.labor()));laborCount++;}
                }
                if(Set.of("FT","CT").contains(d.employee())&&d.labor()!=null)idleCost=idleCost.add(positive(possible.subtract(used)).multiply(BigDecimal.valueOf(d.labor())));
            }
            BigDecimal demand=zero();
            record PlanBucket(long project,String month,String job,String grade) {}
            Map<PlanBucket,BigDecimal> groupedPlans=new LinkedHashMap<>();
            for(var p:plans)groupedPlans.merge(new PlanBucket(p.project(),p.month(),p.job(),grade(p.grade())),p.mm(),BigDecimal::add);
            for(var entry:groupedPlans.entrySet()){
                var p=entry.getKey();
                if(!p.month().equals(month)||!p.job().equals(b.job())||!grade(p.grade()).equals(b.grade()))continue;
                BigDecimal used=actual.stream().filter(x->month.equals(x.month())&&assignments.stream().anyMatch(a->a.id()==x.assignment()&&a.project()==p.project()&&a.job().equals(p.job())&&grade(a.grade()).equals(b.grade())))
                    .map(AssignedMonth::mm).reduce(zero(),BigDecimal::add);
                demand=demand.add(positive(entry.getValue().subtract(used)));
            }
            for(var n:needs){if(!n.job().equals(b.job())||!grade(n.grade()).equals(b.grade()))continue;
                var start=YearMonth.parse(n.start());if(m.isBefore(start)||!m.isBefore(start.plusMonths(n.months())))continue;
                demand=demand.add(BigDecimal.valueOf(n.people()).multiply(n.mm()).multiply(wins.getOrDefault(n.opportunity(),n.probability())).movePointLeft(2));}
            BigDecimal temp=temporary.stream().filter(t->t.job().equals(b.job())&&grade(t.grade()).equals(b.grade())&&month.compareTo(t.start())>=0&&month.compareTo(t.end())<=0)
                .map(Temporary::mm).reduce(zero(),BigDecimal::add);
            BigDecimal remaining=supply.subtract(demand),shortage=positive(remaining.negate());
            BigDecimal utilization=available.signum()==0?null:confirmed.multiply(BigDecimal.valueOf(100)).divide(available,2,RoundingMode.HALF_UP);
            var baseline=includeFinancialBaseline?profit.read(month):null;Long operating=baseline==null?null:baseline.operatingProfit();
            if(operating==null)warnings.add("FUTURE_ACTUAL · 영업이익 기준 원천 없음");if(b.grade().isEmpty())warnings.add("등급 미분류");
            if(laborCount==0)warnings.add("원가 미확인");
            long average=laborCount==0?0:labor.divide(BigDecimal.valueOf(laborCount),0,RoundingMode.DOWN).longValueExact();
            rows.add(new OpportunityForecastModels.Month(month,b.job(),b.grade(),supply,demand,remaining,shortage,available,confirmed,extension,temp,utilization,
                idleCost.setScale(0,RoundingMode.DOWN).longValueExact(),true,operating,average,List.copyOf(warnings)));
        }
        var shortages=new ArrayList<Shortage>();
        for(var b:buckets){
            var all=rows.stream().filter(r->r.job_cd().equals(b.job())&&r.grd_cd().equals(b.grade())).toList();
            var first=all.stream().filter(r->r.shortage_mm().compareTo(new BigDecimal("0.40"))>0).findFirst();if(first.isEmpty())continue;
            BigDecimal maximum=all.stream().map(OpportunityForecastModels.Month::shortage_mm).max(Comparator.naturalOrder()).orElse(zero());
            String start=first.get().month(),recommended=YearMonth.parse(start).minusMonths(2).toString();
            String last=all.stream().filter(r->r.shortage_mm().compareTo(new BigDecimal("0.40"))>0)
                .map(OpportunityForecastModels.Month::month).max(Comparator.naturalOrder()).orElseThrow();
            shortages.add(new Shortage(b.job(),b.grade(),maximum,maximum.setScale(0,RoundingMode.CEILING).intValueExact(),start,recommended,
                recommended.compareTo(YearMonth.from(q.today()).toString())<0,mapper.candidates(new CandidateFilter(b.job(),b.grade(),last))));
        }
        shortages.sort(Comparator.comparing(Shortage::maximum_shortage_mm).reversed().thenComparing(Shortage::job_cd).thenComparing(Shortage::grd_cd));
        rows.sort(workforceOrder(q.sort()));
        return new WorkforcePage(rows.stream().skip((long)q.page()*q.size()).limit(q.size()).toList(),q.page(),q.size(),rows.size(),List.copyOf(shortages),q.months(),90,
            q.months().isEmpty()?"예측할 미래월이 없습니다.":null);
    }
    private static Comparator<OpportunityForecastModels.Month> workforceOrder(String sort){
        String key=sort.split(",")[0];
        Comparator<OpportunityForecastModels.Month> order=switch(key){
            case "job_cd"->Comparator.comparing(OpportunityForecastModels.Month::job_cd);
            case "grd_cd"->Comparator.comparing(OpportunityForecastModels.Month::grd_cd);
            case "supply_mm"->Comparator.comparing(OpportunityForecastModels.Month::supply_mm);
            case "demand_mm"->Comparator.comparing(OpportunityForecastModels.Month::demand_mm);
            case "remaining_mm"->Comparator.comparing(OpportunityForecastModels.Month::remaining_mm);
            case "shortage_mm"->Comparator.comparing(OpportunityForecastModels.Month::shortage_mm);
            case "available_mm"->Comparator.comparing(OpportunityForecastModels.Month::available_mm);
            case "confirmed_mm"->Comparator.comparing(OpportunityForecastModels.Month::confirmed_mm);
            case "extension_mm"->Comparator.comparing(OpportunityForecastModels.Month::extension_mm);
            case "temporary_mm"->Comparator.comparing(OpportunityForecastModels.Month::temporary_mm);
            case "utilization_rate"->Comparator.comparing(OpportunityForecastModels.Month::utilization_rate,Comparator.nullsLast(Comparator.naturalOrder()));
            case "idle_cost"->Comparator.comparingLong(OpportunityForecastModels.Month::idle_cost);
            case "average_labor_cost"->Comparator.comparingLong(OpportunityForecastModels.Month::average_labor_cost);
            default->Comparator.comparing(OpportunityForecastModels.Month::month);
        };
        if(sort.endsWith(",desc"))order=order.reversed();
        return order.thenComparing(OpportunityForecastModels.Month::month).thenComparing(OpportunityForecastModels.Month::job_cd).thenComparing(OpportunityForecastModels.Month::grd_cd);
    }
    @Override public List<CandidateForecastReader.Month> candidateMonths(Map<String,String> filters){
        var q=OpportunityForecastInput.parse(filters,clock);
        var unpaged=new Query(q.months(),q.job(),q.grade(),q.sort(),0,Integer.MAX_VALUE,q.today());
        return forecast(unpaged).items().stream().map(r->new CandidateForecastReader.Month(r.month(),r.job_cd(),r.grd_cd(),r.shortage_mm())).toList();
    }
    public Page opportunityForecast(Map<String,String> filters){
        var q=OpportunityForecastInput.parse(filters,clock);
        var workforce=forecast(new Query(q.months(),q.job(),q.grade(),q.sort(),0,Integer.MAX_VALUE,q.today()));
        var rows=new ArrayList<>(readRevenue(q));
        return revenuePage(q,workforce,rows);
    }
    public List<RevenueMonth> readRevenue(Query q) {
        var projects=mapper.projects();var assignments=mapper.assignments();var actual=mapper.assignmentMonths();var opportunities=mapper.opportunities(q);
        List<RevenueMonth> rows=new ArrayList<>();
        for(String month:q.months()){
            YearMonth m=YearMonth.parse(month);long projectRevenue=0,opportunityRevenue=0,allWon=0;
            for(var p:projects){
                if(p.start()==null||p.end()==null)continue;YearMonth start=YearMonth.from(p.start()),end=YearMonth.from(p.end());if(m.isBefore(start)||m.isAfter(end))continue;
                if("TK".equals(p.type())){
                    if(q.job()!=null||q.grade()!=null){boolean match=assignments.stream().anyMatch(a->a.project()==p.id()&&matches(q,a.job(),grade(a.grade())));if(!match)continue;}
                    if(p.amount()!=null)projectRevenue+=allocation(p.amount(),(int)ChronoUnit.MONTHS.between(start,end)+1,(int)ChronoUnit.MONTHS.between(start,m));
                }else if(Set.of("MO","DP").contains(p.type())){
                    for(var a:assignments){if(a.project()!=p.id()||!matches(q,a.job(),grade(a.grade())))continue;
                        for(var x:actual)if(x.assignment()==a.id()&&x.month().equals(month)&&x.price()!=null)projectRevenue+=x.mm().multiply(BigDecimal.valueOf(x.price())).setScale(0,RoundingMode.DOWN).longValueExact();}
                }
            }
            for(var o:opportunities){var start=YearMonth.parse(o.start());int offset=(int)ChronoUnit.MONTHS.between(start,m);if(offset<0||offset>=o.months())continue;
                long amount=allocation(o.amount(),o.months(),offset);allWon+=amount;
                opportunityRevenue+=BigDecimal.valueOf(amount).multiply(o.probability()).movePointLeft(2).setScale(0,RoundingMode.DOWN).longValueExact();}
            rows.add(new RevenueMonth(month,projectRevenue,opportunityRevenue,projectRevenue+opportunityRevenue,projectRevenue+allWon));
        }
        return List.copyOf(rows);
    }
    private Page revenuePage(Query q,WorkforcePage workforce,ArrayList<RevenueMonth> rows) {
        Comparator<RevenueMonth> order=switch(q.sort().split(",")[0]){case "project_revenue"->Comparator.comparingLong(RevenueMonth::project_revenue);case "opportunity_revenue"->Comparator.comparingLong(RevenueMonth::opportunity_revenue);case "weighted_total"->Comparator.comparingLong(RevenueMonth::weighted_total);case "all_won_total"->Comparator.comparingLong(RevenueMonth::all_won_total);default->Comparator.comparing(RevenueMonth::month);};
        if(q.sort().endsWith(",desc"))order=order.reversed();rows.sort(order.thenComparing(RevenueMonth::month));
        BigDecimal supply=zero(),demand=zero();for(var r:workforce.items()){supply=supply.add(r.supply_mm());demand=demand.add(r.demand_mm());}
        int count=q.months().size();if(count>0){supply=supply.divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);demand=demand.divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);}
        var kpi=new Kpi(rows.stream().mapToLong(RevenueMonth::weighted_total).sum(),rows.stream().mapToLong(RevenueMonth::opportunity_revenue).sum(),rows.stream().mapToLong(RevenueMonth::all_won_total).sum(),demand,supply,workforce.shortages().stream().map(Shortage::job_cd).distinct().count());
        var pagedWorkforce=new WorkforcePage(workforce.items().stream().skip((long)q.page()*q.size()).limit(q.size()).toList(),q.page(),q.size(),workforce.total(),workforce.shortages(),q.months(),90,workforce.message());
        return new Page(rows.stream().skip((long)q.page()*q.size()).limit(q.size()).toList(),q.page(),q.size(),rows.size(),pagedWorkforce,kpi);
    }
    static long allocation(long amount,int count,int index){long base=amount/count;return index==count-1?amount-base*(count-1L):base;}
}
