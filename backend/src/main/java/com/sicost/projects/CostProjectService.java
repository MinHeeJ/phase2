package com.sicost.projects;

import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.billingsops.BillingModels.CostRevenueQuery;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import java.math.*;
import java.time.Clock;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.projects.CostProjectModels.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class CostProjectService {
    private final CostProjectMapper mapper;
    private final CostAllocationService allocations;
    private final BillingRevenueQuery revenue;
    private final PermissionService permissions;
    private final Clock clock;
    public CostProjectService(CostProjectMapper mapper, CostAllocationService allocations,
            BillingRevenueQuery revenue, PermissionService permissions, Clock clock) {
        this.mapper=mapper;
        this.allocations=allocations;
        this.revenue=revenue;
        this.permissions=permissions;
        this.clock=clock;
    }
    private String referenceMonth() { return YearMonth.from(ProjectValues.today(clock)).toString(); }
    private Source require(long id) {
        CostValues.id(id,"id");
        var source=mapper.source(id);
        if (source==null) throw ApiException.notFound("프로젝트가 없습니다.");
        return source;
    }
    private BigDecimal parameter(String key,String month) {
        BigDecimal value=mapper.parameter(key,month);
        if (value==null) throw BillingValues.invalid("month");
        return value;
    }
    public List<Detail> filtered(Query q) {
        permissions.can(CostActions.PROJECT_READ);
        CostValues.year(q.year(),clock);
        var details=mapper.sources(q).stream().map(s->calculate(s,q.year())).toList();
        var sort=q.paging().allowedSort(Set.of("prj_id","prj_no","prj_nm","cumulative_cost_amt",
            "confirmed_supply_amt","profit_amt","margin_rt","budget_use_rt"),"prj_id,asc");
        Comparator<Detail> comparator=(a,b)->compare(a.project(),b.project(),sort.field());
        if (sort.descending()) comparator=comparator.reversed();
        return details.stream().sorted(comparator.thenComparingLong(d->d.project().prj_id())).toList();
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static int compare(Row a,Row b,String field) {
        try {
            Comparable left=(Comparable)Row.class.getMethod(field).invoke(a);
            Comparable right=(Comparable)Row.class.getMethod(field).invoke(b);
            return Comparator.nullsLast(Comparator.<Comparable>naturalOrder()).compare(left,right);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }
    public ProjectList list(Query q) {
        var all=filtered(q);
        long sales=0,profit=0,rnd=0,low=0,over=0;
        var warnings=new LinkedHashSet<String>();
        for (var d:all) {
            Row r=d.project();
            sales=Math.addExact(sales,r.confirmed_supply_amt()==null?0:r.confirmed_supply_amt());
            profit=Math.addExact(profit,r.profit_amt()==null?0:r.profit_amt());
            if ("IN".equals(r.cntrct_ty_cd())) rnd=Math.addExact(rnd,r.cumulative_cost_amt());
            if (r.low_margin()) low++;
            if (r.budget_use_rt()!=null && r.budget_use_rt().compareTo(BigDecimal.valueOf(100))>=0) over++;
            warnings.addAll(d.warnings());
        }
        String month=CostValues.months(q.year(),clock).get(CostValues.months(q.year(),clock).size()-1);
        return new ProjectList(month,referenceMonth(),!all.isEmpty() && all.stream().allMatch(Detail::closed),
            "원",List.copyOf(warnings),all.stream().skip(q.paging().offset()).limit(q.paging().size()).map(Detail::project).toList(),
            all.size(),q.paging().page(),q.paging().size(),new Summary(sales,profit,low,over,rnd));
    }
    public Detail detail(long id,int year) {
        permissions.can(CostActions.PROJECT_READ);
        CostValues.year(year,clock);
        return calculate(require(id),year);
    }
    public Causes causes(long id,int year) { return detail(id,year).causes(); }
    private static BigDecimal mm(AllocationRow r) { return r.mm_qty()==null?BigDecimal.ZERO:r.mm_qty(); }
    private static boolean projectCost(AllocationRow r) { return Set.of("PROJECT","RND").contains(r.aloc_ty_cd()); }
    private static BigDecimal ratio(long profit, long sales) {
        return sales==0?null:BigDecimal.valueOf(profit).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(sales),12,RoundingMode.HALF_UP);
    }
    private static BigDecimal display(BigDecimal value) { return value==null?null:value.setScale(1,RoundingMode.HALF_UP); }
    private static Long per(long amount,BigDecimal mm) {
        return mm.signum()==0?null:CostValues.signedWon(BigDecimal.valueOf(amount).divide(mm,12,RoundingMode.HALF_UP));
    }
    private Detail calculate(Source p,int year) {
        var months=CostValues.months(year,clock);
        String through=months.get(months.size()-1);
        var period=new Period(p.prj_id(),months.get(0),through);
        var allocated=allocations.year(year,p.prj_id());
        var labor=mapper.labor(period);
        var names=new HashMap<Long,String>();
        for (var l:labor) names.put(l.dev_id(),l.dev_nm());
        var salesByMonth=new HashMap<String,Long>();
        for (var r:revenue.projectRevenue(new CostRevenueQuery(year,p.prj_id())))
            if (months.contains(r.month())) salesByMonth.merge(r.month(),r.confirmed_supply_amt(),Math::addExact);
        var references=new HashMap<String,Long>();
        for (var r:mapper.reference(period)) if (r.amount()!=null) references.put(r.month(),r.amount());
        var warnings=new LinkedHashSet<String>();
        boolean internal="IN".equals(p.cntrct_ty_cd());
        long cost=0,lbr=0,expenses=0,sales=0,freeCost=0,costRate=0;
        BigDecimal actual=BigDecimal.ZERO,freeMm=BigDecimal.ZERO;
        BigDecimal threshold=parameter("RENEGO_COST_RATIO",through);
        var monthRows=new ArrayList<Month>();
        var people=new ArrayList<Person>();
        List<AllocationRow> current=new ArrayList<>();
        for (var a:allocated) {
            warnings.addAll(a.warnings());
            long ml=0,me=0,mf=0;
            BigDecimal fm=BigDecimal.ZERO;
            var personCostDifferences=new HashMap<Long,BigDecimal>();
            for (var r:a.items()) {
                if (!projectCost(r)) continue;
                ml=Math.addExact(ml,r.lbr_cst_amt());
                me=Math.addExact(me,r.drct_expns_amt());
                actual=actual.add(mm(r));
                people.add(new Person(names.get(r.dev_id()),r));
                if (r.cst_ym().equals(through)) current.add(r);
                if (r.dev_id()!=null) personCostDifferences.merge(r.dev_id(),BigDecimal.valueOf(r.cost())
                    .subtract(BigDecimal.valueOf(r.sls_amt()).multiply(threshold).movePointLeft(2)),BigDecimal::add);
                if ("Y".equals(r.free_sprt_yn())) { mf=Math.addExact(mf,r.cost()); fm=fm.add(mm(r)); }
            }
            for (var difference:personCostDifferences.values())
                costRate=Math.addExact(costRate,CostValues.signedWon(difference.max(BigDecimal.ZERO)));
            long mc=Math.addExact(ml,me),ms=salesByMonth.getOrDefault(a.calculation_month(),0L);
            cost=Math.addExact(cost,mc); lbr=Math.addExact(lbr,ml); expenses=Math.addExact(expenses,me);
            sales=Math.addExact(sales,ms); freeCost=Math.addExact(freeCost,mf); freeMm=freeMm.add(fm);
            long profit=Math.subtractExact(ms,mc);
            Long monthlyReference=references.get(a.calculation_month());
            if (monthlyReference==null && "TK".equals(p.cntrct_ty_cd()) && p.cur_cntrct_amt()!=null
                    && p.trgt_cst_amt()!=null && p.trgt_cst_amt()>0) {
                monthlyReference=CostValues.signedWon(BigDecimal.valueOf(p.cur_cntrct_amt()).multiply(
                    BigDecimal.valueOf(cost).divide(BigDecimal.valueOf(p.trgt_cst_amt()),12,RoundingMode.HALF_UP).min(BigDecimal.ONE)));
            }
            monthRows.add(new Month(a.calculation_month(),a.closed(),internal?null:ms,ml,me,mc,
                internal?null:profit,internal?null:display(ratio(profit,ms)),fm,mf,
                p.trgt_cst_amt()==null?null:Math.subtractExact(p.trgt_cst_amt(),cost),
                internal?null:monthlyReference));
        }
        BigDecimal planned=mapper.plans(period).stream().map(Plan::mm_qty).reduce(BigDecimal.ZERO,BigDecimal::add);
        var causes=CostProjectCauses.calculate(new CostProjectCauses.Basis(p,planned,actual,cost,costRate,freeCost));
        BigDecimal budgetUse=p.trgt_cst_amt()==null||p.trgt_cst_amt()<=0?null:ratio(cost,p.trgt_cst_amt());
        if (budgetUse==null) warnings.add("목표 원가 없음: 예산 소진율 산정 불가");
        if (sales==0 && !internal) warnings.add("확정 청구 매출 없음: 마진 산정 불가");
        Long estimated=null;
        BigDecimal estimatedMargin=null;
        if ("TK".equals(p.cntrct_ty_cd())) {
            estimated=estimate(p,through,current,labor,cost);
            if (estimated==null) warnings.add("잔여 기간/MM 또는 최근 인건비 없음: 예상 총원가 산정 불가");
            else if (p.cur_cntrct_amt()!=null) estimatedMargin=ratio(Math.subtractExact(p.cur_cntrct_amt(),estimated),p.cur_cntrct_amt());
        }
        long profit=Math.subtractExact(sales,cost);
        BigDecimal margin=ratio(profit,sales), compared=estimatedMargin;
        if (Set.of("MO","DP").contains(p.cntrct_ty_cd())) {
            Month m=monthRows.get(monthRows.size()-1);
            compared=ratio(m.profit_amt(),m.confirmed_supply_amt());
            estimatedMargin=compared;
        }
        BigDecimal goal=p.trgt_mrgn_rt()==null?parameter("TARGET_MARGIN_RATE",through):p.trgt_mrgn_rt();
        boolean low=!internal && compared!=null && goal.subtract(compared).compareTo(parameter("LOW_MARGIN_GAP",through))>0;
        Long ref=null;
        if ("TK".equals(p.cntrct_ty_cd())) {
            if (!references.isEmpty()) ref=references.values().stream().reduce(0L,Math::addExact);
            else if (p.cur_cntrct_amt()!=null && p.trgt_cst_amt()!=null && p.trgt_cst_amt()>0)
                ref=CostValues.signedWon(BigDecimal.valueOf(p.cur_cntrct_amt()).multiply(
                    BigDecimal.valueOf(cost).divide(BigDecimal.valueOf(p.trgt_cst_amt()),12,RoundingMode.HALF_UP).min(BigDecimal.ONE)));
        }
        Row row=new Row(p.prj_id(),p.prj_no(),p.prj_nm(),p.cntrct_ty_cd(),internal?null:p.cur_cntrct_amt(),p.trgt_cst_amt(),
            internal?null:goal,planned,actual,internal?null:sales,lbr,expenses,cost,internal?null:profit,internal?null:display(margin),
            display(budgetUse),p.trgt_cst_amt()==null?null:Math.subtractExact(p.trgt_cst_amt(),cost),estimated,
            internal?null:display(estimatedMargin),internal?null:ref,freeMm,freeCost,internal?null:per(sales,actual),per(cost,actual),
            internal?null:per(profit,actual),low,budgetUse==null?null:budgetUse.compareTo(BigDecimal.valueOf(100))>=0?"위험":
            budgetUse.compareTo(BigDecimal.valueOf(80))>=0?"주의":null,causes.primary_cause(),internal?null:p.init_cntrct_amt());
        return new Detail(through,referenceMonth(),allocated.stream().allMatch(a->a.closed()),"원",List.copyOf(warnings),row,
            List.copyOf(monthRows),List.copyOf(people),causes);
    }
    private Long estimate(Source p,String through,List<AllocationRow> current,List<Labor> labor,long cost) {
        if (p.cur_end_dt()==null) return null;
        long remaining=Math.max(0,ChronoUnit.MONTHS.between(YearMonth.parse(through),YearMonth.from(p.cur_end_dt())));
        if (remaining==0) return cost;
        BigDecimal extra=BigDecimal.ZERO;
        var planned=labor.stream().filter(l->l.future_mm_qty()!=null).toList();
        if (!planned.isEmpty()) {
            for (var l:planned) {
                if (l.monthly_cost_amt()==null) return null;
                extra=extra.add(l.future_mm_qty().multiply(BigDecimal.valueOf(l.monthly_cost_amt())));
            }
        } else {
            var ids=current.stream().filter(r->r.dev_id()!=null && mm(r).signum()>0).map(AllocationRow::dev_id).distinct().toList();
            if (ids.isEmpty()) return null;
            for (Long id:ids) {
                var l=labor.stream().filter(v->v.dev_id()==id).findFirst().orElse(null);
                if (l==null || l.monthly_cost_amt()==null) return null;
                extra=extra.add(BigDecimal.valueOf(l.monthly_cost_amt()).multiply(BigDecimal.valueOf(remaining)));
            }
        }
        return Math.addExact(cost,CostValues.signedWon(extra));
    }
    public Simulation simulate(long id,SimulationInput input) {
        permissions.can(CostActions.PROJECT_READ);
        Source source=require(id);
        Detail baseline=calculate(source,ProjectValues.today(clock).getYear());
        boolean internal="IN".equals(source.cntrct_ty_cd());
        boolean monthly=Set.of("MO","DP").contains(source.cntrct_ty_cd());
        if (monthly && input.monthly_price_amt()==null) throw BillingValues.invalid("monthly_price_amt");
        long headcount=mapper.headcount(new Period(id,referenceMonth(),referenceMonth()));
        if (input.headcount_delta() < -headcount) throw BillingValues.invalid("headcount_delta");
        try {
            long multiplier=Math.multiplyExact((long)input.headcount_delta(),input.months());
            long additional=Math.multiplyExact(multiplier,input.monthly_cost_amt());
            long cost=Math.addExact(baseline.project().cumulative_cost_amt(),additional);
            if (cost<0) throw BillingValues.invalid("monthly_cost_amt");
            Long addRevenue=internal?null:monthly?Math.multiplyExact(multiplier,input.monthly_price_amt()):0L;
            Long sales=internal?null:Math.addExact(baseline.project().confirmed_supply_amt(),addRevenue);
            Long profit=internal?null:Math.subtractExact(sales,cost);
            return new Simulation(true,baseline.project().cumulative_cost_amt(),additional,cost,addRevenue,sales,profit,
                internal?null:display(ratio(profit,sales)),baseline.warnings());
        } catch (ArithmeticException ex) { throw BillingValues.invalid("amount"); }
    }
}
