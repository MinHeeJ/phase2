package com.sicost.closingops;

import com.sicost.billingsops.*;
import com.sicost.common.api.*;
import com.sicost.costallocation.CostAllocationService;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.closingops.ClosingModels.*;

@Service
public class ClosingCheckService {
    private final ClosingMapper mapper;
    private final CostAllocationService costs;
    private final BillingGapService gaps;
    public ClosingCheckService(ClosingMapper mapper,CostAllocationService costs,BillingGapService gaps) {
        this.mapper=mapper;this.costs=costs;this.gaps=gaps;
    }
    public List<Check> calculate(String month) {
        var checks=new ArrayList<>(mapper.sourceChecks(month));
        try {
            var allocation=costs.calculate(month,null);
            int issues=(int)allocation.warnings().stream().filter(w->!w.contains("1.0 초과")).count();
            if(allocation.unallocated_cost_amt()!=0)issues++;
            if(issues>0)replace(checks,"MM_OVER",new Check("MM_OVER","FAIL",issues,"급여·MM·단가·미배분 원천을 확인하세요."));
        } catch(ApiException ex) { replace(checks,"MM_OVER",new Check("MM_OVER","FAIL",1,"원가 원천 계산에 실패했습니다.")); }
        try {
            var rows=gaps.filtered(new BillingModels.GapQuery(month,null,null,new BillingPaging(0,20,"project_name,asc")));
            int issues=(int)rows.stream().filter(g->!Set.of("맞음","맞음(무상)").contains(g.judgment())).count();
            checks.add(new Check("BILL_MISSING",issues==0?"OK":"WARN",issues,issues==0?"정상":"청구 누락·금액 차이를 확인하세요."));
        } catch(ApiException ex) { checks.add(new Check("BILL_MISSING","FAIL",1,"청구 원천 계산에 실패했습니다.")); }
        if(checks.size()!=9 || checks.stream().map(Check::code).distinct().count()!=9)throw new IllegalStateException("9종 점검 원천이 필요합니다.");
        return List.copyOf(checks);
    }
    private void replace(List<Check> checks,String code,Check result) {
        for(int i=0;i<checks.size();i++)if(code.equals(checks.get(i).code())) {checks.set(i,result);return;}
        throw new IllegalStateException("점검 코드가 없습니다.");
    }
}
