package com.sicost.analysis;

import com.sicost.common.ai.AiDataConventions;
import com.sicost.opportunitiesops.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.analysis.AnalysisModels.*;

@Service
public class WorkforceAnalysisTools {
    private final WorkforceForecastService workforce;
    private final OpportunityForecastMapper mapper;
    private final Clock clock;
    public WorkforceAnalysisTools(WorkforceForecastService workforce,OpportunityForecastMapper mapper,Clock clock) {
        this.workforce=workforce;this.mapper=mapper;this.clock=clock;
    }
    public OpportunityForecastModels.Query sourceQuery(Query query,List<String> months) {
        var f=query.filters();if(!f.projectIds().isEmpty()||!f.customerIds().isEmpty()||!f.partIds().isEmpty()||!f.devIds().isEmpty()||!f.skillCodes().isEmpty())throw AnalysisInput.invalid("filters");
        if(f.jobCodes().size()>1||f.gradeCodes().size()>1)throw AnalysisInput.invalid("filters");
        return new OpportunityForecastModels.Query(months,f.jobCodes().isEmpty()?null:f.jobCodes().get(0),f.gradeCodes().isEmpty()?null:f.gradeCodes().get(0),"month,asc",0,Integer.MAX_VALUE,LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
    }
    public List<WorkforceRow> read(Query query,List<String> months) {
        var wins=new HashMap<Long,BigDecimal>();var extensions=new HashMap<Long,BigDecimal>();
        for(var assumption:query.workforceAssumptions()) {
            if("OPPORTUNITY_WIN".equals(assumption.type())) {
                if(mapper.needs().stream().noneMatch(n->n.opportunity()==assumption.opportunityId()))throw AnalysisInput.invalid("opportunityId");
                if(wins.put(assumption.opportunityId(),assumption.probability())!=null)throw AnalysisInput.invalid("workforceAssumptions");
            } else {
                if(mapper.assignments().stream().noneMatch(a->a.id()==assumption.assignmentId()))throw AnalysisInput.invalid("assignmentId");
                if(extensions.put(assumption.assignmentId(),assumption.probability())!=null)throw AnalysisInput.invalid("workforceAssumptions");
            }
        }
        return workforce.readNonSensitive(sourceQuery(query,months),wins,extensions).items().stream().map(r->new WorkforceRow(r.month(),r.job_cd(),r.grd_cd(),
            AiDataConventions.twoDecimals(r.supply_mm()),AiDataConventions.twoDecimals(r.demand_mm()),AiDataConventions.twoDecimals(r.confirmed_mm()),AiDataConventions.twoDecimals(r.temporary_mm()),
            AiDataConventions.twoDecimals(r.available_mm().subtract(r.confirmed_mm()).max(BigDecimal.ZERO)),r.utilization_rate(),r.demand_mm().compareTo(r.supply_mm())>0?"인력 부족 위험 높음":"인력 부족 위험 낮음")).toList();
    }
    public List<SalesRow> sales(Query query,List<String> months) {
        return workforce.readRevenue(sourceQuery(query,months)).stream().map(r->new SalesRow(r.month(),BigDecimal.valueOf(r.project_revenue()),BigDecimal.ZERO,
            BigDecimal.valueOf(r.opportunity_revenue()),BigDecimal.valueOf(r.all_won_total()-r.project_revenue()),BigDecimal.valueOf(r.project_revenue()),
            BigDecimal.valueOf(r.weighted_total()),BigDecimal.valueOf(r.all_won_total()))).toList();
    }
}
