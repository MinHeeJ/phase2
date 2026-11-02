package com.sicost.closingops;

import com.sicost.apiops.ClosingQueryService;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ClosingValues;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.profit.MonthlyProfitService;
import com.sicost.cashflow.CashFlowService;
import com.sicost.tax.CorporateTaxService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.closingops.ClosingModels.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ClosingSnapshotService {
    private final ClosingMapper mapper;
    private final CostAllocationService costs;
    private final MonthlyProfitService profits;
    private final CorporateTaxService taxes;
    private final CashFlowService cash;
    private final ClosingQueryService stored;
    public ClosingSnapshotService(ClosingMapper mapper,CostAllocationService costs,MonthlyProfitService profits,
            CorporateTaxService taxes,CashFlowService cash,ClosingQueryService stored) {
        this.mapper=mapper;this.costs=costs;this.profits=profits;this.taxes=taxes;this.cash=cash;this.stored=stored;
    }
    public Preview preview(String requested) {
        String month=ClosingValues.month(requested,"month").toString();
        if("CLOSED".equals(mapper.status(month))) {
            stored.getClosing(month);
            var p=mapper.preview(month);
            if(p==null)throw conflict("MISSING_CLOSED_SNAPSHOT","마감 스냅샷이 없습니다.");
            return new Preview(month,p.profit(),p.cost(),p.tax(),p.cash(),List.of());
        }
        var c=components(month);
        return new Preview(month,amount(c.profit(),"operating_profit"),amount(c.profit(),"cost"),c.tax(),c.cash(),c.warnings());
    }
    public Components components(String month) {
        YearMonth ym=ClosingValues.month(month,"month");
        if("IMPORT".equals(mapper.profitSource(month)))throw conflict("IMPORT_CONFLICT","이관 손익이 있는 월은 마감할 수 없습니다.");
        var allocation=costs.calculate(month,null);
        if(allocation.unallocated_cost_amt()!=0)throw conflict("CLOSE_CHECK_FAILED","미배분 원가가 있습니다.");
        com.sicost.tax.CorporateTaxModels.Plan plan;
        try {plan=taxes.plan(ym.getYear());}
        catch(ApiException ex) {if(ex.status()==404)throw conflict("CLOSE_CHECK_FAILED","연간 법인세 계획을 입력하세요.");throw ex;}
        var tax=plan.months().stream().filter(m->m.month().equals(month)).findFirst().orElseThrow();
        if(tax.amount()==null)throw conflict("ANNUAL_BELOW_CLOSED".equals(tax.warning())?"TAX_BELOW_CLOSED":"MISSING_CLOSED_SNAPSHOT","법인세 계획·마감 월할을 확인하세요.");
        var profit=profits.calculate(ym.getYear()).get(ym.getMonthValue()-1);
        for(String key:List.of("revenue","project_labor_cost","idle_cost","direct_expenses","sga_input","indirect_labor_cost","rnd_cost","previous_adjustment","non_operating_income","non_operating_expenses","operating_profit","cost"))amount(profit,key);
        if(amount(profit,"corporate_tax")!=tax.amount())throw conflict("TAX_BELOW_CLOSED","손익과 법인세 월할이 일치하지 않습니다.");
        var flow=cash.flow(ym.getYear(),"ACTUAL").months().stream().filter(m->m.month().equals(month)).findFirst().orElseThrow();
        if(flow.actual_opening()==null||flow.actual_closing()==null)throw conflict("OPENING_BALANCE_REQUIRED","기초 잔액을 확인하세요.");
        var details=new ArrayList<>(profits.snapshotDetails(month));details.addAll(mapper.sgaDetails(month));
        var warnings=new LinkedHashSet<>(allocation.warnings());
        if(profit.get("warnings") instanceof List<?> list)list.forEach(w->warnings.add(w.toString()));
        if(tax.warning()!=null)warnings.add(tax.warning());if(flow.warning()!=null)warnings.add(flow.warning());
        if(mapper.unlinkedTaxPayments(month)>0)warnings.add("UNLINKED_TAX_PAYMENT: 지급 연결이 없는 법인세는 계획으로만 반영됩니다. 수동 대사하세요.");
        return new Components(month,allocation.items(),profit,List.copyOf(details),tax.amount(),flow.actual_opening(),flow.actual_closing(),List.copyOf(warnings));
    }
    public static long amount(Map<String,Object> values,String key) {
        Object n=values.get(key);
        if(!(n instanceof Number))throw conflict("CLOSE_CHECK_FAILED","계산에 필요한 원천이 없습니다: "+key);
        return ((Number)n).longValue();
    }
    static ApiException conflict(String code,String message) {return ApiException.conflict(code,message,"month");}
}
