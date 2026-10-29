package com.sicost.billingsops;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly=true)
public class BillingReceivableService {
    private final BillingMapper mapper;
    private final Clock clock;
    private final PermissionService permissions;
    public BillingReceivableService(BillingMapper mapper,Clock clock,PermissionService permissions) {
        this.mapper=mapper;this.clock=clock;this.permissions=permissions;
    }
    public Row row(State s) {
        long remaining="Y".equals(s.amnd_yn())?0:BillingRules.receivable(
            BillingRules.Status.valueOf(s.bill_stts_cd()),s.adjusted_total_amt(),s.received_amt());
        Integer days=days(s.rcpt_plan_dt());
        return new Row(s.bill_id(),s.bill_stg_id(),false,s.bill_no(),s.prj_id(),s.project_name(),
            s.customer_id(),s.customer_name(),s.contract_type(),s.atrb_ym(),s.bill_dt(),s.sply_amt(),
            s.vat_amt(),s.tot_amt(),s.bill_stts_cd(),s.txinv_no(),s.txinv_dt(),s.rcpt_plan_dt(),
            s.received_amt(),s.adjusted_total_amt(),remaining,remaining>0?stage(days):null);
    }
    public Integer days(LocalDate due) {
        return due==null?null:Math.toIntExact(ChronoUnit.DAYS.between(due,ProjectValues.today(clock)));
    }
    public static Integer stage(Integer days) {
        return days==null?null:days>=60?3:days>=30?2:days>=7?1:0;
    }
    public static LocalDate next(LocalDate due,Integer days,long balance) {
        if(due==null || days==null || balance<=0 || days>=60)return null;
        return due.plusDays(days<7?7:days<30?30:60);
    }
    public static List<String> roles(Integer stage,String part,String hq) {
        LinkedHashSet<String> roles=new LinkedHashSet<>();
        if(stage!=null && stage>=1) {roles.add("영업 담당자");roles.add("PM");}
        if(stage!=null && stage>=2)roles.add(part==null?"경영진":"Y".equals(hq)?"본사 영업 책임자":"영업 파트장");
        if(stage!=null && stage>=3) {roles.add("경영진");roles.add("재무");}
        return List.copyOf(roles);
    }
    public List<Overdue> filtered(OverdueQuery q) {
        permissions.can(BillingActions.READ);
        var sort=q.paging().allowedSort(Set.of("overdue_days","bill_id","project_name","customer_name","receivable_amt","rcpt_plan_dt","stage"),"overdue_days,desc");
        List<Overdue> result=new ArrayList<>();
        for(State s:mapper.states(q.year())) {
            Row b=row(s);
            if(b.receivable_amt()<=0 || !"CONFIRM".equals(s.bill_stts_cd()) || "Y".equals(s.amnd_yn()))continue;
            Integer days=days(b.rcpt_plan_dt()),stage=stage(days);
            if(q.stage()!=null && !Objects.equals(stage,q.stage()))continue;
            if(q.customerId()!=null && !Objects.equals(b.customer_id(),q.customerId()))continue;
            if(q.overDays()!=null && (days==null || days<=q.overDays()))continue;
            if(!matches(q.q(),b.project_name(),b.customer_name(),b.bill_no()))continue;
            result.add(new Overdue(b,days,stage,days==null?"예정일 미입력":days<0?"예정":days==0?"당일":"연체",
                roles(stage,s.sales_part(),s.hq_yn()),next(b.rcpt_plan_dt(),days,b.receivable_amt()),mapper.sent(s.bill_id()),s.sales_part()));
        }
        Comparator<Overdue> order=switch(sort.field()) {
            case "bill_id" -> Comparator.comparing(r->r.billing().bill_id());
            case "project_name" -> Comparator.comparing(r->r.billing().project_name());
            case "customer_name" -> Comparator.comparing(r->r.billing().customer_name(),Comparator.nullsLast(Comparator.naturalOrder()));
            case "receivable_amt" -> Comparator.comparingLong(r->r.billing().receivable_amt());
            case "rcpt_plan_dt" -> Comparator.comparing(r->r.billing().rcpt_plan_dt(),Comparator.nullsLast(Comparator.naturalOrder()));
            case "stage" -> Comparator.comparing(Overdue::stage,Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(Overdue::overdue_days,Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if(sort.descending())order=order.reversed();
        if(sort.field().equals("overdue_days"))order=Comparator.comparing(Overdue::overdue_days,
            Comparator.nullsLast(sort.descending()?Comparator.<Integer>reverseOrder():Comparator.<Integer>naturalOrder()));
        result.sort(order.thenComparing(r->r.billing().bill_id()));
        return List.copyOf(result);
    }
    public Overdues list(OverdueQuery query) {
        var all=filtered(query);
        Map<Long,CustomerTotal> customers=new LinkedHashMap<>();
        for(var row:all) {
            var b=row.billing();var prior=customers.get(b.customer_id());
            customers.put(b.customer_id(),new CustomerTotal(b.customer_id(),b.customer_name(),
                BillingValues.add(prior==null?0:prior.receivable_amt(),b.receivable_amt(),"receivable_amt")));
        }
        List<StageCount> counts=new ArrayList<>();
        for(int i=0;i<=3;i++) {final int stage=i;counts.add(new StageCount(i,all.stream().filter(r->Objects.equals(r.stage(),stage)).count()));}
        return new Overdues(page(all,query.paging()),List.copyOf(customers.values()),List.copyOf(counts),
            all.size(),query.paging().page(),query.paging().size());
    }
    static boolean matches(String q,String... values) {
        if(q==null || q.isBlank())return true;
        String search=q.toLowerCase(Locale.ROOT);
        return Arrays.stream(values).filter(Objects::nonNull).anyMatch(v->v.toLowerCase(Locale.ROOT).contains(search));
    }
    static <T> List<T> page(List<T> all,BillingPaging paging) {
        return all.stream().skip(paging.offset()).limit(paging.size()).toList();
    }
}
