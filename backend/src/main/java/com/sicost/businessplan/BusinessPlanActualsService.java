package com.sicost.businessplan;

import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.billingsops.BillingModels.CostRevenueQuery;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.people.PeopleCostService;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.businessplan.BusinessPlanModels.*;

@Service
@Transactional(readOnly=true)
public class BusinessPlanActualsService {
    private final BusinessPlanMapper mapper;
    private final BillingRevenueQuery billing;
    private final PeopleCostService people;
    private final ObjectProvider<MonthlyProfitReader> profit;
    private final Clock clock;
    public BusinessPlanActualsService(BusinessPlanMapper mapper,BillingRevenueQuery billing,PeopleCostService people,
            ObjectProvider<MonthlyProfitReader> profit,Clock clock) {
        this.mapper=mapper;this.billing=billing;this.people=people;this.profit=profit;this.clock=clock;
    }
    public List<Actual> read(int year,Long partId) {
        var current=YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var closed=mapper.closedMonths(year);
        var result=new ArrayList<Actual>();
        var reader=profit.getIfAvailable();
        var projects=year<=current.getYear()&&partId!=null?billing.projectRevenue(new CostRevenueQuery(year,null)):null;
        var company=year<=current.getYear()&&partId==null?billing.revenue(year):null;
        var attribution=partId==null?List.<Attribution>of():mapper.attributions(year);
        for(int m=1;m<=12;m++) {
            String month=YearMonth.of(year,m).toString();
            var warnings=new ArrayList<String>();
            Long revenue=null,operating=null;
            java.math.BigDecimal utilization=null;
            if(YearMonth.parse(month).isAfter(current))warnings.add("FUTURE_ACTUAL: "+month+" 실적 미산정");
            else if(year==2025)warnings.add("IMPORT_NOT_AVAILABLE: 사업계획 비교에는 2025 이관자료를 사용하지 않습니다.");
            else {
                boolean isClosed=closed.contains(month);
                if(isClosed) {
                    var snapshot=mapper.closedActual(month);
                    if(snapshot==null)warnings.add("MISSING_CLOSED_SNAPSHOT: "+month);
                    else if(partId==null){revenue=snapshot.revenue();operating=snapshot.operating_profit();}
                } else if(partId==null&&reader!=null) {
                    var value=reader.read(month);
                    if(value!=null){revenue=value.revenue();operating=value.operatingProfit();warnings.addAll(value.warnings());}
                    else warnings.add("MISSING_PROFIT_SOURCE: "+month);
                }
                if(partId!=null) {
                    revenue=0L;
                    boolean missingAttribution=false;
                    for(var p:projects)if(p.month().equals(month)) {
                        var assigned=attribution.stream().filter(a->a.projectId()==p.prj_id()&&a.month().equals(month)).findFirst().orElse(null);
                        if(assigned==null||assigned.partId()==null) {
                            missingAttribution=true;warnings.add("MISSING_PART_ATTRIBUTION: "+p.prj_id()+" "+month);
                        }
                        else if(Objects.equals(partId,assigned.partId()))revenue=Math.addExact(revenue,p.confirmed_supply_amt());
                    }
                    if(missingAttribution)revenue=null;
                    warnings.add("MISSING_PART_PROFIT_SOURCE: 파트 영업이익 원천 자료 없음 · "+month);
                } else if(!isClosed&&reader==null) {
                    revenue=company.stream().filter(r->r.month().equals(month)).mapToLong(r->r.confirmed_supply_amt()).reduce(0,Math::addExact);
                    warnings.add("MISSING_PROFIT_SOURCE: 공통 월 손익 조회 연결 필요 · "+month);
                }
                // Never ask the cost engine to replace a missing CLOSED snapshot.
                if(!isClosed||!warnings.stream().anyMatch(w->w.startsWith("MISSING_CLOSED_SNAPSHOT"))) {
                    var summary=people.monthlyUtilization(month,partId);
                    utilization=summary.utilization_rt();
                    if(utilization==null)warnings.add("MISSING_UTILIZATION: "+month+" DIRECT 분모 없음");
                }
            }
            result.add(new Actual(m,revenue,operating,utilization,List.copyOf(new LinkedHashSet<>(warnings))));
        }
        return List.copyOf(result);
    }
}
