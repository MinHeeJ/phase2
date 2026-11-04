package com.sicost.analysis;

import com.sicost.cashflow.*;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.analysis.AnalysisModels.*;
import static com.sicost.common.ai.AiDataConventions.*;

@Service
public class ForecastAnalysisTools {
    private final CashFlowService cash;
    private final FinancialAnalysisTools finance;
    private final AnalysisMapper mapper;
    private final Clock clock;
    public ForecastAnalysisTools(CashFlowService cash,FinancialAnalysisTools finance,AnalysisMapper mapper,Clock clock) {
        this.cash=cash;this.finance=finance;this.mapper=mapper;this.clock=clock;
    }
    public List<CashRow> cash(Query query,List<String> months) {
        if(!query.filters().references().isEmpty())throw AnalysisInput.invalid("filters");
        var years=new HashMap<Integer,CashFlowModels.Flow>();var rows=new ArrayList<CashRow>();
        try {
            for(String month:months) {
                var flow=years.computeIfAbsent(YearMonth.parse(month).getYear(),y->cash.readAnalysisFlow(y,LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))));
                var r=flow.months().stream().filter(x->month.equals(x.month())).findFirst().orElse(null);if(r==null)continue;
                var base=r.forecast_closing()==null?null:BigDecimal.valueOf(r.forecast_closing());
                rows.add(new CashRow(month,decimal(r.forecast_opening()),decimal(r.forecast_inflow()),decimal(r.forecast_outflow()),null,base,null,
                    base==null?null:base.min(BigDecimal.ZERO).negate()));
            }
        } catch(ApiException e) {if(!"OPENING_BALANCE_REQUIRED".equals(e.code()))throw e;}
        return List.copyOf(rows);
    }
    public List<ProfitForecastRow> profit(Query query,List<String> months) {
        return finance.finance(query,months).stream().map(r->new ProfitForecastRow(r.month(),null,r.operatingProfit(),null,
            "검증된 기회 인건비·직접원가 차이가 없어 범위 계산 불가")).toList();
    }
    public List<TurnkeyRow> turnkey(Query query,List<String> months) {
        var rows=new ArrayList<TurnkeyRow>();
        for(var p:mapper.projects(query.filters()))if("TK".equals(p.type())) {
            var historical=new Query(Intent.PROJECT_PROFIT_TREND,query.baseYm(),null,null,"MONTH",query.sort(),query.limit(),
                new Filters(List.of(p.id()),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>()),new ArrayList<>(),false);
            var actual=finance.projects(historical,months);BigDecimal accumulated=FinancialAnalysisTools.sum(actual.stream().map(ProfitRow::cost).toList());
            rows.add(new TurnkeyRow(p.id(),p.amount(),accumulated,null,null,null,null,"검증 가능한 잔여원가 원천 없음 · 최종 마진 추정 불가"));
        }
        return List.copyOf(rows);
    }
    public List<Evaluation> evaluations(Query query) {
        var rows=new ArrayList<Evaluation>();
        for(var old:mapper.savedForecasts(query.baseYm())) {
            BigDecimal actual=null;boolean closed="CLOSED".equals(finance.status(old.targetYm()));
            if(closed) {
                if(Set.of("REVENUE","OP_PROFIT").contains(old.type())){
                    var values=finance.finance(query,List.of(old.targetYm()));if(!values.isEmpty())actual="REVENUE".equals(old.type())?values.get(0).revenue():values.get(0).operatingProfit();
                }
                if("CASH".equals(old.type())) {var values=cash(query,List.of(old.targetYm()));if(!values.isEmpty())actual=values.get(0).base();}
            }
            rows.add(evaluate(old,actual,closed));
        }
        return List.copyOf(rows);
    }
    static Evaluation evaluate(SavedForecast old,BigDecimal actual,boolean closed) {
        BigDecimal difference=closed&&actual!=null&&old.forecastValue()!=null?old.forecastValue().subtract(actual).abs():null;
        return new Evaluation(old.baseYm(),old.targetYm(),"OP_PROFIT".equals(old.type())?"OPERATING_PROFIT":old.type(),old.forecastValue(),closed?actual:null,
            difference,difference==null?null:ratioPercent(difference,actual.abs()),difference==null?"평가 대기":"평가 완료");
    }
    private static BigDecimal decimal(Long n) {return n==null?null:BigDecimal.valueOf(n);}
}
