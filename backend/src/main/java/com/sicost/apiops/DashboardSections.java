package com.sicost.apiops;

import com.sicost.cashflow.CashFlowService;
import com.sicost.opportunities.OpportunityModels.PipelineRead;
import com.sicost.opportunities.OpportunityService;
import com.sicost.parts.PartSettlementService;
import com.sicost.projects.CostProjectModels;
import com.sicost.projects.CostProjectService;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.apiops.DashboardModels.*;

/** Presentation-only composition. Every business value is supplied by an existing owner. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class DashboardSections {
    private final ProjectBoardService board;
    private final CostProjectService costs;
    private final PartSettlementService parts;
    private final OpportunityService opportunities;
    private final CashFlowService cash;
    public DashboardSections(ProjectBoardService board,CostProjectService costs,PartSettlementService parts,
            OpportunityService opportunities,CashFlowService cash) {
        this.board=board;this.costs=costs;this.parts=parts;this.opportunities=opportunities;this.cash=cash;
    }
    private static final CardState READY=new CardState("READY",null,null);
    public record ProjectRead(ProjectAlerts alerts,Map<String,Integer> counts) {}
    public ProjectRead projects(DashboardInput.Context context) {
        var source=board.readDashboard(context.today());
        var details=costs.readForProjectsAt(source.rows().stream().map(ProjectBoardModels.BoardRow::prj_id).toList(),context.today().getYear(),context.today());
        var byId=new HashMap<Long,CostProjectModels.Detail>();
        details.forEach(d->byId.put(d.project().prj_id(),d));
        var result=new ArrayList<ProjectAlert>();
        for(var row:source.rows()) {
            var detail=byId.get(row.prj_id());
            var cost=detail==null?null:detail.project();
            var statuses=new LinkedHashSet<String>();
            if(!"정상".equals(row.light())) {statuses.addAll(row.reasons());statuses.add(row.light());}
            if(cost!=null && cost.low_margin())statuses.add("저마진");
            if(cost!=null && cost.budget_warning()!=null)statuses.add("예산 "+cost.budget_warning());
            boolean missing=detail==null || detail.warnings().stream().anyMatch(w->w.contains("급여") || w.contains("배분자료") || w.contains("원가 미"));
            if(statuses.isEmpty())continue;
            var cause=detail==null?null:detail.causes().items().stream().filter(c->c.amount()>0)
                .sorted(Comparator.comparingLong(CostProjectModels.Cause::amount).reversed()
                    .thenComparingLong(CostProjectModels.Cause::project_id).thenComparing(CostProjectModels.Cause::cause_code)).findFirst().orElse(null);
            boolean risk="위험".equals(row.light()) || cost!=null && "위험".equals(cost.budget_warning());
            result.add(new ProjectAlert(row.prj_id(),row.prj_nm(),risk?"RISK":"CAUTION",List.copyOf(statuses),
                missing || cost==null?null:cost.estimated_margin_rt(),
                cost==null?row.cost_use_rt():cost.budget_use_rt(),cause==null?null:new CostCause(cause.project_id(),cause.cause_code(),cause.amount()),
                missing?"MISSING_COST":"READY"));
        }
        result.sort(Comparator.comparingInt((ProjectAlert r)->"RISK".equals(r.severity())?0:1)
            .thenComparing(ProjectAlert::budgetUsagePercent,Comparator.nullsLast(Comparator.reverseOrder())).thenComparingLong(ProjectAlert::projectId));
        var counts=new HashMap<String,Integer>();
        counts.put("RISK_PROJECT",(int)source.rows().stream().filter(r->"위험".equals(r.light())).count());
        counts.put("UNASSIGNED_PART",source.unassignedCount());
        counts.put("OVERDUE_ISSUE",(int)source.rows().stream().filter(r->r.overdue_issue_count()>0).count());
        counts.put("STALE_PROJECT_UPDATE",(int)source.rows().stream().filter(r->r.memo_elapsed_days()!=null && r.memo_elapsed_days()>14).count());
        counts.put("LOW_MARGIN",(int)details.stream().filter(d->d.project().low_margin()).map(d->d.project().prj_id()).distinct().count());
        return new ProjectRead(new ProjectAlerts(READY,context.today(),List.copyOf(result)),Map.copyOf(counts));
    }
    public PartSummary parts(DashboardInput.Context context) {
        var items=parts.readSummary(context.basisMonth().toString()).stream()
            .map(r->new PartRow(r.id(),r.name(),r.classification(),r.revenue(),r.margin(),r.marginState())).toList();
        boolean incomplete=items.stream().anyMatch(r->r.revenue()==null || "MISSING_COST".equals(r.marginState()));
        return new PartSummary(incomplete?new CardState("PARTIAL","일부 파트 자료 확인 필요",null):READY,
            context.basisMonth().toString(),"파트 이익률",items);
    }
    public PipelineRead pipelineSource(DashboardInput.Context context) { return opportunities.readPipeline(context.today()); }
    public Pipeline pipeline(DashboardInput.Context context,PipelineRead source) {
        return new Pipeline(READY,context.today(),source.openCount(),source.weightedAmount(),
            context.currentMonth().plusMonths(1).toString(),context.currentMonth().plusMonths(3).toString(),source.startingTotal(),
            source.starting().stream().map(r->new PipelineOpportunity(r.oppt_id(),r.oppt_nm(),YearMonth.parse(r.bgn_ym()).atDay(1),
                r.bsn_stg_cd(),r.win_prblt_rt(),r.expc_amt(),OpportunityService.weighted(r))).toList());
    }
    public CashForecast cash(DashboardInput.Context context) {
        List<com.sicost.cashflow.CashFlowModels.ForecastMonth> source;
        try {source=cash.readForecast(context.today());}
        catch(com.sicost.common.api.ApiException ex) {
            if(!"OPENING_BALANCE_REQUIRED".equals(ex.code()))throw ex;
            source=new ArrayList<>();
        }
        var months=new ArrayList<CashPoint>();
        for(int i=1;i<=3;i++) {
            String month=context.currentMonth().plusMonths(i).toString();
            var row=source.stream().filter(r->month.equals(r.month())).findFirst().orElse(null);
            Long balance=row==null?null:row.closing(),minimum=row==null?null:row.minimum();
            boolean missing=balance==null || minimum==null;
            months.add(new CashPoint(month,balance,minimum,missing?null:balance<minimum,missing?"MISSING":"READY"));
        }
        return new CashForecast(months.stream().anyMatch(p->"MISSING".equals(p.state()))?
            new CardState("MISSING","기초 잔액·최소 운영자금 자료 확인 필요",null):READY,context.today(),
            months.get(0).month(),months.get(2).month(),true,List.copyOf(months));
    }
}
