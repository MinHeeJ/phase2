package com.sicost.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.common.api.ApiException;
import com.sicost.common.costallocation.CostAllocationService;
import com.sicost.common.costallocation.CostAllocationModels.Allocation;
import com.sicost.common.security.*;
import java.math.*;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.projects.CostProjectModels.*;
import static com.sicost.common.costallocation.CostAnalysisInput.invalid;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class CostProjectService {
    private final CostProjectMapper mapper;
    private final CostAllocationService allocation;
    private final BillingRevenueQuery revenue;
    private final PermissionService permissions;
    private final Clock clock;
    public CostProjectService(CostProjectMapper mapper, CostAllocationService allocation,
        BillingRevenueQuery revenue, PermissionService permissions, Clock clock) {
        this.mapper=mapper;this.allocation=allocation;this.revenue=revenue;this.permissions=permissions;this.clock=clock;
    }
    public Query query(Map<String,String> input) { return CostProjectInput.query(input,clock); }
    private String reference() { return YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString(); }
    private Source require(long id,Query q) {
        var source=mapper.source(new Period(id,q.from(),q.month()));
        if(source==null)throw ApiException.notFound("프로젝트가 없습니다."); return source;
    }
    public Page list(Query q) { return list(q,false); }
    public Page exportRows(Query q) { return list(q,true); }
    private Page list(Query q,boolean all) {
        permissions.can(all?CostAnalysisActions.PROJECTS_DOWNLOAD:CostAnalysisActions.PROJECTS_READ);
        var details=mapper.sources(q).stream().map(source->calculate(source,q)).toList();
        var rows=details.stream().map(Detail::project).sorted(order(q)).toList();
        var warnings=details.stream().flatMap(d->d.warnings().stream()).distinct().toList();
        var summary=new Summary(rows.size(),sum(rows.stream().filter(r->!"IN".equals(r.cntrct_ty_cd())).map(Row::confirmed_supply_amt).toList()),
            sum(rows.stream().filter(r->!"IN".equals(r.cntrct_ty_cd())).map(Row::profit_amt).toList()),
            rows.stream().filter(Row::low_margin).count(),rows.stream().filter(r->"DANGER".equals(r.budget_warning())).count(),
            sum(rows.stream().filter(r->"IN".equals(r.cntrct_ty_cd())).map(Row::rnd_cost_amt).toList()));
        return new Page(q.month(),details.stream().allMatch(d->"CLOSED".equals(d.status())) && !details.isEmpty()?"CLOSED":"OPEN",
            reference(),"KRW",warnings,all?rows:rows.stream().skip(q.paging().offset()).limit(q.paging().size()).toList(),
            rows.size(),q.paging().page(),q.paging().size(),summary);
    }
    public Detail detail(long id,Query q) {
        permissions.can(CostAnalysisActions.PROJECTS_READ); return calculate(require(id,q),q);
    }
    public Causes causes(long id,Query q) { return detail(id,q).causes(); }
    public Simulation simulate(long id,JsonNode body) {
        permissions.can(CostAnalysisActions.PROJECTS_READ);
        var q=query(Map.of("year",reference().substring(0,4)));
        var p=require(id,q);var input=CostProjectInput.simulation(body,p.cntrct_ty_cd());
        if(p.headcount()+input.headcount_delta()<0)throw invalid("headcount_delta");
        try {
            BigDecimal factor=BigDecimal.valueOf(input.headcount_delta()).multiply(BigDecimal.valueOf(input.months()));
            long cost=factor.multiply(BigDecimal.valueOf(input.monthly_cost_amt())).longValueExact();
            long sales=input.monthly_price_amt()==null?0:factor.multiply(BigDecimal.valueOf(input.monthly_price_amt())).longValueExact();
            long profit=Math.subtractExact(sales,cost);
            return new Simulation(q.month(),"OPEN",reference(),"KRW",List.of(),true,cost,sales,profit);
        } catch(ArithmeticException ex) {throw invalid("monthly_cost_amt");}
    }
    private Detail calculate(Source p,Query q) {
        boolean internal="IN".equals(p.cntrct_ty_cd()),tk="TK".equals(p.cntrct_ty_cd());
        var warnings=new LinkedHashSet<String>();
        if(p.plan_mm_qty()==null)warnings.add("인원 계획 MM 없음");
        var billed=revenue.projectRevenue(p.prj_id(),q.year());
        boolean missingRevenue=billed.stream().anyMatch(BillingRevenueQuery.ProjectRevenue::missing_bill_date);
        if(missingRevenue)warnings.add("확정 청구일 누락");
        var names=new HashMap<Long,String>(); mapper.people(p.prj_id()).forEach(n->names.put(n.dev_id(),n.dev_nm()));
        var months=new ArrayList<Month>();var people=new ArrayList<Person>();var allocations=new ArrayList<Allocation>();
        Long cumulative=0L;
        for(YearMonth m=YearMonth.parse(q.from());!m.isAfter(YearMonth.parse(q.month()));m=m.plusMonths(1)) {
            String month=m.toString();var result=allocation.get(month,p.prj_id());
            var relevant=result.items().stream().filter(a->Set.of("PROJECT","RND").contains(a.aloc_ty_cd())).toList();
            allocations.addAll(relevant);warnings.addAll(result.warnings());
            Long labor=sum(relevant.stream().map(Allocation::lbr_cst_amt).toList());
            long expense=0;
            for(var a:relevant)expense=Math.addExact(expense,a.drct_expns_amt());
            for(var e:result.project_expenses())if(Set.of("PROJECT","RND").contains(e.aloc_ty_cd()))expense=Math.addExact(expense,e.drct_expns_amt());
            Long cost=labor==null?null:Math.addExact(labor,expense);
            cumulative=add(cumulative,cost);
            Long sales=internal || missingRevenue?null:0L;
            if(sales!=null)for(var r:billed)if(month.equals(r.month()))sales=Math.addExact(sales,r.confirmed_supply_amt());
            Long profit=subtract(sales,cost);
            BigDecimal mm=relevant.stream().map(Allocation::mm_qty).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
            var free=relevant.stream().filter(a->"Y".equals(a.free_sprt_yn())).toList();
            BigDecimal freeMm=free.stream().map(Allocation::mm_qty).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
            Long freeCost=sum(free.stream().map(a->add(a.lbr_cst_amt(),a.drct_expns_amt())).toList());
            Long ref=tk?mapper.referenceRevenue(new Period(p.prj_id(),month,month)):null;
            if(tk && ref==null && p.cur_cntrct_amt()!=null && positive(p.trgt_cst_amt()) && cost!=null)
                ref=BigDecimal.valueOf(p.cur_cntrct_amt()).multiply(BigDecimal.valueOf(cost).divide(BigDecimal.valueOf(p.trgt_cst_amt()),16,RoundingMode.HALF_UP).min(BigDecimal.ONE)).setScale(0,RoundingMode.DOWN).longValueExact();
            months.add(new Month(month,result.status(),sales,labor,expense,cost,profit,ratio(profit,sales),mm,freeMm,freeCost,
                internal?null:subtract(p.trgt_cst_amt(),cumulative),ref));
            for(var a:relevant)people.add(new Person(month,a.dev_id(),names.getOrDefault(a.dev_id(),"개발자 정보 없음"),a.asgn_id(),
                a.mm_qty(),a.lbr_cst_amt(),a.drct_expns_amt(),internal?null:a.sls_amt(),a.free_sprt_yn()));
        }
        Long labor=sum(months.stream().map(Month::lbr_cst_amt).toList());
        long expenses=months.stream().mapToLong(Month::drct_expns_amt).reduce(0,Math::addExact);
        Long cost=sum(months.stream().map(Month::total_cost_amt).toList());
        Long sales=internal?null:sum(months.stream().map(Month::confirmed_supply_amt).toList());
        Long profit=subtract(sales,cost);BigDecimal margin=ratio(profit,sales);
        BigDecimal mm=months.stream().map(Month::mm_qty).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal freeMm=months.stream().map(Month::free_mm_qty).reduce(BigDecimal.ZERO,BigDecimal::add);
        Long freeCost=sum(months.stream().map(Month::free_cost_amt).toList());
        BigDecimal expected=null;
        if(tk) {
            if(p.remaining_cost_amt()!=null && cost!=null && p.cur_cntrct_amt()!=null)expected=ratio(
                BigDecimal.valueOf(p.cur_cntrct_amt()).subtract(BigDecimal.valueOf(cost)).subtract(p.remaining_cost_amt()),decimal(p.cur_cntrct_amt()));
            else warnings.add("남은 MM·금액 기준 누락: 예상 최종 마진 산정불가");
        }
        BigDecimal monthly=Set.of("MO","DP").contains(p.cntrct_ty_cd())?months.get(months.size()-1).margin_rt():null;
        BigDecimal use=internal || !positive(p.trgt_cst_amt())?null:ratio(cost,p.trgt_cst_amt());
        if(!internal && !positive(p.trgt_cst_amt()))warnings.add("목표 원가 없음: 예산 소진율 산정불가");
        if(!internal && p.trgt_mrgn_rt()==null)warnings.add("목표 마진율 없음");
        if(!internal && p.bsn_part_id()==null)warnings.add("영업 파트 미지정");
        if(labor==null)warnings.add("급여 미업로드: 원가·마진 산정불가");
        BigDecimal view=tk?expected:monthly;
        BigDecimal rawMargin=rawRatio(decimal(profit),decimal(sales));
        BigDecimal rawView=tk && p.remaining_cost_amt()!=null && cost!=null && p.cur_cntrct_amt()!=null
            ?rawRatio(BigDecimal.valueOf(p.cur_cntrct_amt()).subtract(BigDecimal.valueOf(cost)).subtract(p.remaining_cost_amt()),decimal(p.cur_cntrct_amt()))
            :Set.of("MO","DP").contains(p.cntrct_ty_cd())?rawRatio(decimal(months.get(months.size()-1).profit_amt()),decimal(months.get(months.size()-1).confirmed_supply_amt())):null;
        boolean low=low(rawMargin,p.trgt_mrgn_rt()) || low(rawView,p.trgt_mrgn_rt());
        BigDecimal rawUse=positive(p.trgt_cst_amt())?rawRatio(decimal(cost),decimal(p.trgt_cst_amt())):null;
        String budget=internal?null:rawUse==null?"UNAVAILABLE":rawUse.compareTo(new BigDecimal("100"))>=0?"DANGER":rawUse.compareTo(new BigDecimal("80"))>=0?"WARNING":"NORMAL";
        var cause=explain(p,q,allocations,cost,mm,freeCost,warnings,months.stream().allMatch(m->"CLOSED".equals(m.status()))?"CLOSED":"OPEN");
        var row=new Row(p.prj_id(),p.prj_no(),p.prj_nm(),p.cntrct_ty_cd(),p.prj_stts_cd(),p.bsn_part_id(),p.part_name(),
            internal?null:p.init_cntrct_amt(),internal?null:p.cur_cntrct_amt(),internal?null:p.trgt_cst_amt(),internal?null:p.trgt_mrgn_rt(),
            p.plan_mm_qty(),mm,sales,labor,expenses,cost,profit,margin,expected,monthly,use,
            view==null || p.trgt_mrgn_rt()==null?null:view.subtract(p.trgt_mrgn_rt()),
            per(sales,mm),per(cost,mm),per(profit,mm),freeMm,freeCost,internal?cost:null,budget,low,cause.main_cause(),List.copyOf(warnings));
        return new Detail(q.month(),cause.status(),reference(),"KRW",List.copyOf(warnings),row,List.copyOf(months),List.copyOf(people),
            tk?sum(months.stream().map(Month::reference_revenue_amt).toList()):null,cause);
    }
    private Causes explain(Source p,Query q,List<Allocation> rows,Long cost,BigDecimal mm,Long free,
        Set<String> warnings,String status) {
        BigDecimal excess=p.plan_mm_qty()==null?null:mm.subtract(p.plan_mm_qty()).max(BigDecimal.ZERO);
        BigDecimal per=per(cost,mm);
        Long excessCost=excess==null || per==null?null:excess.multiply(per).setScale(0,RoundingMode.DOWN).longValueExact();
        BigDecimal rate=mapper.costRate(q.month());
        Long priceCost=0L;
        if(rate==null) {warnings.add("재협상 원가 기준 없음");priceCost=null;}
        else {
            var byPerson=new LinkedHashMap<Long,List<Allocation>>();
            rows.forEach(a->byPerson.computeIfAbsent(a.dev_id(),key->new ArrayList<>()).add(a));
            for(var person:byPerson.values()) {
                Long total=sum(person.stream().map(a->add(a.lbr_cst_amt(),a.drct_expns_amt())).toList());
                Long reference=sum(person.stream().map(Allocation::sls_amt).toList());
                if(total==null || reference==null) {priceCost=null;warnings.add("개인 매출·원가 누락: 원인 산정불가");break;}
                long amount=BigDecimal.valueOf(total).subtract(BigDecimal.valueOf(reference).multiply(rate).movePointLeft(2))
                    .max(BigDecimal.ZERO).setScale(0,RoundingMode.DOWN).longValueExact();
                priceCost=Math.addExact(priceCost,amount);
            }
        }
        var items=new ArrayList<Cause>();
        items.add(new Cause("EXCESS_MM","투입 초과",excessCost,"max(실제 MM − 계획 MM, 0) × MM당 원가",p.owner(),"인원 계획 대비 투입 확인",
            "/UI-11?projectId="+p.prj_id()+"&year="+q.year(),true));
        items.add(new Cause("COST_PRICE","단가 대비 원가",priceCost,"인력별 max(배분 원가 − 개인 매출 × 기준율, 0)",p.owner(),"단가 재협상 · 승인 대기",null,false));
        items.add(new Cause("FREE_SUPPORT","무상지원",free,"무상지원 배분 원가 (총원가에 이미 포함)",p.owner(),"프로젝트 원가 분석 확인",
            "/UI-04?projectId="+p.prj_id()+"&year="+q.year(),true));
        items.sort(Comparator.comparing(Cause::amount,Comparator.nullsLast(Comparator.reverseOrder())));
        String main=items.stream().filter(c->c.amount()!=null && c.amount()>0).map(Cause::code).findFirst().orElse(null);
        BigDecimal progress=null,residual=null;
        if("TK".equals(p.cntrct_ty_cd()) && cost!=null && positive(p.trgt_cst_amt()) && p.actl_prgs_rt()!=null) {
            progress=BigDecimal.valueOf(cost).subtract(BigDecimal.valueOf(p.trgt_cst_amt()).multiply(p.actl_prgs_rt()).movePointLeft(2));
            Long explained=sum(items.stream().map(Cause::amount).toList());
            if(explained!=null)residual=progress.subtract(BigDecimal.valueOf(explained));
        }
        return new Causes(q.month(),status,reference(),"KRW",List.copyOf(warnings),p.prj_id(),q.year(),List.copyOf(items),main,progress,residual);
    }
    static boolean low(BigDecimal actual,BigDecimal target) { return actual!=null && target!=null && target.subtract(actual).compareTo(new BigDecimal("5"))>0; }
    static BigDecimal decimal(Long value) {return value==null?null:BigDecimal.valueOf(value);}
    static boolean positive(Long value) {return value!=null && value>0;}
    static Long add(Long a,Long b) {return a==null || b==null?null:Math.addExact(a,b);}
    static Long subtract(Long a,Long b) {return a==null || b==null?null:Math.subtractExact(a,b);}
    static Long sum(List<Long> values) {Long total=0L;for(Long value:values)total=add(total,value);return total;}
    static BigDecimal ratio(Long value,Long denominator) {return ratio(decimal(value),decimal(denominator));}
    private static BigDecimal rawRatio(BigDecimal value,BigDecimal denominator) {
        return value==null || denominator==null || denominator.signum()==0?null:value.multiply(new BigDecimal("100")).divide(denominator,16,RoundingMode.HALF_UP);
    }
    static BigDecimal ratio(BigDecimal value,BigDecimal denominator) {
        return value==null || denominator==null || denominator.signum()==0?null:value.multiply(new BigDecimal("100")).divide(denominator,1,RoundingMode.HALF_UP);
    }
    static BigDecimal per(Long value,BigDecimal denominator) {
        return value==null || denominator==null || denominator.signum()==0?null:BigDecimal.valueOf(value).divide(denominator,16,RoundingMode.HALF_UP);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private Comparator<Row> order(Query q) {
        var sort=q.paging().allowedSort(Set.of("prj_id","prj_no","prj_nm","cntrct_ty_cd","prj_stts_cd","confirmed_supply_amt","total_cost_amt","profit_amt","margin_rt","budget_use_rt","actual_mm_qty","plan_mm_qty"),"prj_nm,asc");
        Comparator<Comparable> scalar=Comparator.naturalOrder();if(sort.descending())scalar=scalar.reversed();
        return Comparator.comparing((Row row)->(Comparable)switch(sort.field()) {
            case "prj_id"->row.prj_id();case "prj_no"->row.prj_no();case "cntrct_ty_cd"->row.cntrct_ty_cd();
            case "prj_stts_cd"->row.prj_stts_cd();case "confirmed_supply_amt"->row.confirmed_supply_amt();
            case "total_cost_amt"->row.total_cost_amt();case "profit_amt"->row.profit_amt();case "margin_rt"->row.margin_rt();
            case "budget_use_rt"->row.budget_use_rt();case "actual_mm_qty"->row.actual_mm_qty();case "plan_mm_qty"->row.plan_mm_qty();
            default->row.prj_nm();
        },Comparator.nullsLast(scalar)).thenComparingLong(Row::prj_id);
    }
}
