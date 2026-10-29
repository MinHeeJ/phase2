package com.sicost.billingsops;

import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly=true)
public class BillingGapService {
    private final BillingMapper mapper;
    private final BillingAmountService amounts;
    private final Clock clock;
    private final PermissionService permissions;
    public BillingGapService(BillingMapper mapper,BillingAmountService amounts,Clock clock,PermissionService permissions) {
        this.mapper=mapper;this.amounts=amounts;this.clock=clock;this.permissions=permissions;
    }
    public String month(String requested) {
        return requested==null || requested.isBlank()?BillingValues.defaultMonth(mapper.lastClosedMonth(),clock).toString():
            BillingValues.month(requested,"month").toString();
    }
    public static String judgment(long basis,long billed,int confirmed,boolean missing,boolean free) {
        if(missing)return "근태 미입력";
        if(free && billed==0)return "맞음(무상)";
        if(basis==0 && confirmed==0)return null;
        if(basis>0 && confirmed==0)return "청구 없음";
        BigDecimal difference=BigDecimal.valueOf(basis).subtract(BigDecimal.valueOf(billed));
        if(difference.abs().compareTo(tolerance(basis))<=0)return "맞음";
        return difference.signum()>0?"적게 청구":"많이 청구";
    }
    public static BigDecimal tolerance(long basis) {
        return BigDecimal.valueOf(basis).abs().multiply(new BigDecimal("0.005")).max(new BigDecimal("10000"));
    }
    public List<Gap> filtered(GapQuery q) {
        permissions.can(BillingActions.READ);
        var sort=q.paging().allowedSort(Set.of("project_name","project_id","basis_supply_amt","confirmed_supply_amt","difference_amt","judgment"),"project_name,asc");
        String month=month(q.month());List<Gap> rows=new ArrayList<>();
        for(Project p:mapper.gapProjects(month)) {
            if(!BillingReceivableService.matches(q.q(),p.project_name()))continue;
            Scope scope=new Scope(p.project_id(),month,null);
            var sources=mapper.sources(scope);var confirmed=mapper.confirmed(scope);
            long basis=0,billed=0;boolean missing=false;
            for(Source s:sources) {
                if(!"MONTH".equals(p.basis()) && (s.atnd_id()==null ||
                    ("DAY".equals(p.basis())?s.work_day_qty()==null:s.work_tm_qty()==null)))missing=true;
                basis=BillingValues.add(basis,amounts.sourceAmount(p,s),"basis_supply_amt");
            }
            for(var bill:confirmed)billed=BillingValues.add(billed,bill.sply_amt(),"confirmed_supply_amt");
            boolean free=!sources.isEmpty() && sources.stream().allMatch(s->"FREE".equals(s.kind()));
            String judgment=judgment(basis,billed,confirmed.size(),missing,free);
            if(judgment==null || (q.judgment()!=null && !q.judgment().isBlank() && !q.judgment().equals(judgment)))continue;
            long difference=BillingValues.money(BigDecimal.valueOf(basis).subtract(BigDecimal.valueOf(billed)),Long.MIN_VALUE,"difference_amt");
            List<Person> suspected=new ArrayList<>();long newSupply=0;
            for(Source s:sources)if(!s.start_date().isBefore(YearMonth.parse(month).atDay(1)) && !"FREE".equals(s.kind())) {
                suspected.add(new Person(s.display_name(),s.management_number()));
                newSupply=BillingValues.add(newSupply,amounts.sourceAmount(p,s),"basis_supply_amt");
            }
            boolean suspect=difference>0 && !suspected.isEmpty() && BigDecimal.valueOf(difference).subtract(BigDecimal.valueOf(newSupply)).abs().compareTo(tolerance(basis))<=0;
            String explanation=missing?"근태 미입력: 근태를 입력한 후 다시 확인하세요.":suspect?"신규 투입자 청구 누락 가능성이 있습니다.":"차이 = 투입 기준 공급가 − 확정 청구 공급가 (확정 수정 포함)";
            if(sources.stream().anyMatch(s->s.price()==null && !"FREE".equals(s.kind())))explanation+=" · 청구 단가 미입력";
            rows.add(new Gap(p.project_id(),p.project_name(),month,basis,billed,difference,tolerance(basis),judgment,
                explanation,missing,suspect?List.copyOf(suspected):List.copyOf(new ArrayList<Person>()),confirmed.stream().map(Confirmed::bill_id).toList()));
        }
        Comparator<Gap> order=switch(sort.field()) {
            case "project_id" -> Comparator.comparingLong(Gap::project_id);
            case "basis_supply_amt" -> Comparator.comparingLong(Gap::basis_supply_amt);
            case "confirmed_supply_amt" -> Comparator.comparingLong(Gap::confirmed_supply_amt);
            case "difference_amt" -> Comparator.comparingLong(Gap::difference_amt);
            case "judgment" -> Comparator.comparing(Gap::judgment);
            default -> Comparator.comparing(Gap::project_name);
        };
        rows.sort((sort.descending()?order.reversed():order).thenComparingLong(Gap::project_id));
        return List.copyOf(rows);
    }
    public Gaps list(GapQuery query) {
        String month=month(query.month());
        var rows=filtered(new GapQuery(month,query.judgment(),query.q(),query.paging()));long sum=0;
        for(var row:rows)sum=BillingValues.add(sum,row.difference_amt(),"total_difference_amt");
        return new Gaps(month,sum,BillingReceivableService.page(rows,query.paging()),rows.size(),query.paging().page(),query.paging().size());
    }
}
