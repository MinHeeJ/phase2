package com.sicost.analysis;

import com.sicost.common.finance.ClosingObserver;
import com.sicost.common.ai.AiDataConventions;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sicost.analysis.AnalysisModels.*;

@Service
public class OfficialForecastService implements ClosingObserver {
    private final AnalysisMapper mapper;
    private final WorkforceAnalysisTools workforce;
    private final ForecastAnalysisTools forecasts;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public OfficialForecastService(AnalysisMapper mapper,WorkforceAnalysisTools workforce,ForecastAnalysisTools forecasts,Clock clock,PlatformTransactionManager manager) {
        this.mapper=mapper;this.workforce=workforce;this.forecasts=forecasts;this.clock=clock;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public void confirmed(String month) {
        for(String type:List.of("REVENUE","OPERATING_PROFIT","STAFF","CASH"))try {
            transaction.executeWithoutResult(tx->record(month,type));
        }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(OfficialForecastService.class).warn("Official forecast unavailable: {} {}",month,type);}
    }
    void record(String month,String type) {
        if(mapper.wasClosed(month))return;
        Intent intent=switch(type){case "REVENUE"->Intent.SALES_PIPELINE_FORECAST;case "OPERATING_PROFIT"->Intent.FORECAST_PROFIT;case "STAFF"->Intent.FORECAST_STAFF;default->Intent.FORECAST_CASH;};
        var filters=new Filters(new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>());
        var query=new Query(intent,month,null,null,"MONTH","PROFIT_DESC",5,filters,new ArrayList<>(),false);
        var months=AnalysisRouter.months(query);
        Long execution=mapper.forecast(new ForecastWrite(month,AiDataConventions.forecastCode(type),months.size(),"{\"source\":\"close-commit\",\"range\":\"business-assumptions-only\",\"missing\":\"null-not-zero\"}",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")))));
        if(execution==null)return;
        switch(type) {
            case "REVENUE" -> {for(var row:workforce.sales(query,months))save(execution,row.month(),"",row.low(),row.base(),row.high());}
            case "OPERATING_PROFIT" -> {for(var row:forecasts.profit(query,months))save(execution,row.month(),"",row.low(),row.base(),row.high());}
            case "STAFF" -> {var rows=workforce.read(query,months);for(String target:months){var selected=rows.stream().filter(r->r.month().equals(target)).toList();
                BigDecimal value=selected.isEmpty()?null:selected.stream().map(r->r.supplyMm().subtract(r.demandMm())).reduce(BigDecimal.ZERO,BigDecimal::add);save(execution,target,"",null,value,null);}}
            default -> {var rows=forecasts.cash(query,months);for(String target:months){var row=rows.stream().filter(r->r.month().equals(target)).findFirst().orElse(null);save(execution,target,"",row==null?null:row.low(),row==null?null:row.base(),row==null?null:row.high());}}
        }
    }
    private void save(long execution,String month,String key,BigDecimal low,BigDecimal base,BigDecimal high){mapper.forecastValue(new ForecastValue(execution,month,key,low,base,high));}
}
