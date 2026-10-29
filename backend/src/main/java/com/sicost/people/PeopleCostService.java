package com.sicost.people;

import com.sicost.apiops.ProjectCostService;
import com.sicost.common.api.ApiException;
import com.sicost.common.costs.CostAllocationModels.*;
import com.sicost.common.security.*;
import java.math.*;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.people.PeopleCostModels.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class PeopleCostService {
    private final PeopleCostMapper mapper;
    private final ProjectCostService costs;
    private final Clock clock;
    private final PermissionService permissions;
    public PeopleCostService(PeopleCostMapper mapper, ProjectCostService costs, Clock clock, PermissionService permissions) {
        this.mapper=mapper; this.costs=costs; this.clock=clock; this.permissions=permissions;
    }
    private YearMonth now() { return YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    private PeopleCostQuery query(Map<String,String> input) { return PeopleCostQuery.parse(input,now()); }
    private record Source(List<PersonMonth> population, List<Evidence> evidence, List<String> warnings,
            Map<Long,List<String>> personWarnings, int months, String through) {}
    private Source source(PeopleCostQuery q) {
        YearMonth through=q.through(now());
        var period=new Period(YearMonth.of(q.year(),1).toString(),through.toString());
        var population=mapper.population(period);
        Map<Long,AssignmentInfo> info=mapper.assignments(period).stream().collect(Collectors.toMap(AssignmentInfo::asgn_id,a->a));
        List<Evidence> evidence=new ArrayList<>();
        List<String> warnings=new ArrayList<>();
        Map<Long,List<String>> byPerson=new HashMap<>();
        for (YearMonth m=YearMonth.of(q.year(),1); !m.isAfter(through); m=m.plusMonths(1)) {
            Result result=costs.allocation(m.toString(),null);
            for (var w:result.warnings()) {
                String message=m+" · "+w.message(); warnings.add(message);
                if (w.dev_id()!=null) byPerson.computeIfAbsent(w.dev_id(),k->new ArrayList<>()).add(message);
            }
            for (var a:result.items()) {
                var assignment=info.get(a.asgn_id());
                String type=assignment==null?null:assignment.contract_type();
                Long revenue=a.sls_amt();
                if (q.excludeTurnkeyPrice() && "TK".equals(type)) revenue=0L;
                if (a.dev_id()==null) continue;
                Long profit=null;
                if(revenue!=null) profit=revenue-a.lbr_cst_amt()-a.drct_expns_amt();
                else if("IDLE".equals(a.aloc_ty_cd())) profit=-a.lbr_cst_amt()-a.drct_expns_amt();
                evidence.add(new Evidence(a.dev_id(),m.toString(),a.prj_id(),a.asgn_id(),assignment==null?
                    ("IDLE".equals(a.aloc_ty_cd())?"대기":"간접"):assignment.project(),a.aloc_ty_cd(),type,
                    assignment==null?null:assignment.job(),a.mm_qty()==null?BigDecimal.ZERO:a.mm_qty(),
                    q.excludeTurnkeyPrice()&&"TK".equals(type)?null:a.bill_untprc_amt(),a.lbr_cst_amt(),a.drct_expns_amt(),
                    revenue,profit,a.mm_ovr(),a.free_sprt(),a.pre_cntrct()));
            }
            for (var u:result.unallocated()) evidence.add(new Evidence(u.dev_id(),m.toString(),null,null,"미배분","UNALLOCATED",null,null,
                BigDecimal.ZERO,null,u.lbr_cst_amt(),u.drct_expns_amt(),null,null,false,false,false));
        }
        // Evidence identity remains on source allocations, including IDLE and unallocated rows.
        return new Source(population,evidence,warnings,byPerson,through.getMonthValue(),through.toString());
    }
    private boolean matches(PersonMonth p, PeopleCostQuery q) {
        return (q.empType()==null||q.empType().equals(p.emp_se_cd()))
            && (q.partId()==null||q.partId().equals(p.bsn_part_id()))
            && (q.vendorId()==null||q.vendorId().equals(p.vendor_id()))
            && (q.job()==null||q.job().equals(p.job()));
    }
    private static BigDecimal money(BigDecimal value,int months) { return value.divide(BigDecimal.valueOf(months),0,RoundingMode.FLOOR); }
    static BigDecimal divide(BigDecimal value,BigDecimal denominator,int scale) {
        return denominator.signum()==0?null:value.divide(denominator,scale,RoundingMode.HALF_UP);
    }
    private static BigDecimal sum(List<Evidence> rows,java.util.function.ToLongFunction<Evidence> f) {
        return rows.stream().map(r->BigDecimal.valueOf(f.applyAsLong(r))).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private static BigDecimal salary(List<PersonMonth> rows,java.util.function.Function<PersonMonth,Long> f) {
        return rows.stream().map(f).filter(Objects::nonNull).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private record Owned(long id, Evidence row) {}
    private List<Owned> owned(Source s,PeopleCostQuery q) {
        return s.evidence().stream().map(e->new Owned(e.dev_id(),e)).toList();
    }
    public People list(Map<String,String> input) {
        permissions.can(CostActions.key("listCostPeople"));
        var q=query(input); var prepared=prepare(q);
        int from=(int)Math.min((long)q.page()*q.size(),prepared.rows().size());
        return new People(prepared.rows().subList(from,Math.min(from+q.size(),prepared.rows().size())),prepared.rows().size(),
            q.page(),q.size(),prepared.through(),prepared.summary(),prepared.contributions(),prepared.warnings());
    }
    public Prepared prepare(PeopleCostQuery q) {
        var s=source(q); var owned=owned(s,q);
        Map<Long,List<PersonMonth>> population=s.population().stream().collect(Collectors.groupingBy(PersonMonth::dev_id));
        List<Row> rows=new ArrayList<>();
        for (var entry:population.entrySet()) {
            long id=entry.getKey(); var months=entry.getValue().stream().filter(p->matches(p,q)).toList();
            if (months.isEmpty()) continue;
            Set<String> included=months.stream().map(PersonMonth::month).collect(Collectors.toSet());
            var es=owned.stream().filter(o->o.id()==id && included.contains(o.row().month())).map(Owned::row)
                .filter(e->q.contractType()==null||q.contractType().equals(e.contract_type())).toList();
            if (q.contractType()!=null && es.isEmpty()) continue;
            var p=months.get(months.size()-1);
            int divisor="AVERAGE".equals(q.view())?s.months():1;
            BigDecimal labor=sum(es,Evidence::labor),expenses=sum(es,Evidence::expenses);
            BigDecimal revenue=sum(es,e->e.revenue()==null?0:e.revenue());
            BigDecimal cost=labor.add(expenses),profit=revenue.subtract(cost);
            boolean overlap=es.stream().anyMatch(Evidence::overlap);
            if (q.lossOnly()&&profit.signum()>=0 || q.overlapOnly()&&!overlap) continue;
            var prices=es.stream().filter(e->e.price()!=null).map(e->BigDecimal.valueOf(e.price())).toList();
            // TK price is a person-month allocation amount, not a company billing amount.
            if (!q.excludeTurnkeyPrice()) {
                var tk=es.stream().filter(e->"TK".equals(e.contract_type())&&e.revenue()!=null)
                    .collect(Collectors.groupingBy(Evidence::month));
                var all=new ArrayList<>(prices); tk.values().forEach(v->all.add(sum(v,e->e.revenue()))); prices=all;
            }
            BigDecimal price=prices.isEmpty()?null:divide(prices.stream().reduce(BigDecimal.ZERO,BigDecimal::add),BigDecimal.valueOf(prices.size()),0);
            rows.add(new Row(id,p.dev_nm(),p.affiliation(),p.position(),p.emp_se_cd(),p.job(),p.grade(),
                es.stream().map(Evidence::project).distinct().collect(Collectors.joining(", ")),q.year()+"-01 ~ "+s.through(),
                es.stream().map(Evidence::contract_type).filter(Objects::nonNull).distinct().collect(Collectors.joining(", ")),
                es.stream().map(Evidence::mm).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(divisor),2,RoundingMode.HALF_UP),price,
                money(salary(months,PersonMonth::salary),divisor),money(salary(months,PersonMonth::allowance),divisor),
                money(salary(months,PersonMonth::insurance),divisor),money(salary(months,PersonMonth::retirement),divisor),
                money(labor,divisor),money(expenses,divisor),money(cost,divisor),money(revenue,divisor),money(profit,divisor),
                divide(profit.multiply(BigDecimal.valueOf(100)),revenue,1),overlap,months.stream().allMatch(PersonMonth::on_leave),
                s.personWarnings().getOrDefault(id,Collections.emptyList())));
        }
        Comparator<Row> comparator=switch(q.sort().split(",")[0]) {
            case "profit" -> Comparator.comparing(Row::profit);
            case "cost" -> Comparator.comparing(Row::cost);
            default -> Comparator.comparing(Row::name);
        };
        if(q.sort().endsWith("desc"))comparator=comparator.reversed();
        rows.sort(comparator.thenComparingLong(Row::dev_id));
        return new Prepared(List.copyOf(rows),summary(rows,s,owned,q),contributions(rows),s.warnings(),s.through());
    }
    private Summary summary(List<Row> rows,Source source,List<Owned> owned,PeopleCostQuery q) {
        Set<Long> ids=rows.stream().map(Row::dev_id).collect(Collectors.toSet());
        BigDecimal active=BigDecimal.ZERO,paid=BigDecimal.ZERO,utilization=BigDecimal.ZERO,earning=BigDecimal.ZERO;
        boolean denominator=false;
        for(int i=1;i<=source.months();i++) {
            String month=YearMonth.of(q.year(),i).toString();
            var direct=source.population().stream().filter(p->ids.contains(p.dev_id())&&p.month().equals(month)
                &&matches(p,q)&&"DIRECT".equals(p.duty_se_cd())).map(PersonMonth::dev_id).collect(Collectors.toSet());
            var es=owned.stream().filter(o->direct.contains(o.id())&&o.row().month().equals(month)
                &&(q.contractType()==null||q.contractType().equals(o.row().contract_type()))).toList();
            long a=es.stream().filter(o->o.row().mm().signum()>0).map(Owned::id).distinct().count();
            long b=es.stream().filter(o->o.row().mm().signum()>0&&!o.row().free()
                &&Set.of("MO","DP").contains(Objects.toString(o.row().contract_type(),""))).map(Owned::id).distinct().count();
            active=active.add(BigDecimal.valueOf(a)); paid=paid.add(BigDecimal.valueOf(b));
            if(!direct.isEmpty()) {
                denominator=true;
                utilization=utilization.add(BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(direct.size()),12,RoundingMode.HALF_UP));
                earning=earning.add(BigDecimal.valueOf(b).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(direct.size()),12,RoundingMode.HALF_UP));
            }
        }
        var prices=rows.stream().map(Row::price).filter(Objects::nonNull).toList();
        var working=rows.stream().filter(r->!r.on_leave()).toList();
        BigDecimal revenue=rows.stream().map(Row::revenue).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal cost=rows.stream().map(Row::cost).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal profit=rows.stream().map(Row::profit).reduce(BigDecimal.ZERO,BigDecimal::add);
        return new Summary(rows.size(),divide(active,BigDecimal.valueOf(source.months()),1),divide(paid,BigDecimal.valueOf(source.months()),1),
            denominator?divide(utilization,BigDecimal.valueOf(source.months()),1):null,denominator?divide(earning,BigDecimal.valueOf(source.months()),1):null,
            divide(prices.stream().reduce(BigDecimal.ZERO,BigDecimal::add),BigDecimal.valueOf(prices.size()),0),
            divide(working.stream().map(Row::cost).reduce(BigDecimal.ZERO,BigDecimal::add),BigDecimal.valueOf(working.size()),0),
            divide(working.stream().map(Row::profit).reduce(BigDecimal.ZERO,BigDecimal::add),BigDecimal.valueOf(working.size()),0),
            (int)rows.stream().filter(r->r.profit().signum()<0).count(),revenue,cost,profit);
    }
    private List<Contribution> contributions(List<Row> rows) {
        List<Contribution> result=new ArrayList<>();
        for(String dimension:List.of("job","grade")) {
            var groups=rows.stream().filter(r->!r.on_leave()).collect(Collectors.groupingBy(r->Objects.toString("job".equals(dimension)?r.job():r.grade(),"미지정"),TreeMap::new,Collectors.toList()));
            groups.forEach((name,group)->result.add(new Contribution(dimension,name,group.size(),
                divide(group.stream().map(Row::profit).reduce(BigDecimal.ZERO,BigDecimal::add),BigDecimal.valueOf(group.size()),0))));
        }
        return result;
    }
    public Breakdown breakdown(String devId,Map<String,String> input) {
        permissions.can(CostActions.key("getCostPersonBreakdown"));
        Long id=PeopleCostQuery.id(devId,"devId");
        if(id==null)throw PeopleCostQuery.invalid("devId");
        var q=query(input);
        if(mapper.person(id)==null)throw ApiException.notFound("개발자가 없습니다.");
        var s=source(q);
        return new Breakdown(id,s.through(),q.view(),owned(s,q).stream().filter(o->o.id()==id).map(Owned::row).toList(),
            s.personWarnings().getOrDefault(id,Collections.emptyList()));
    }
}
