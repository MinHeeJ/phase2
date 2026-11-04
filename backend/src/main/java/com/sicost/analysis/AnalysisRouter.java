package com.sicost.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.apiops.*;
import com.sicost.common.ai.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static com.sicost.analysis.AnalysisModels.*;
import com.sicost.analysis.AnalysisModels.Period;
import static com.sicost.common.ai.AiDataConventions.*;

@Service
public class AnalysisRouter {
    private final FinancialAnalysisTools financial;
    private final WorkforceAnalysisTools workforce;
    private final ForecastAnalysisTools forecasts;
    private final AiSourceReads reads;
    private final AiSensitiveGuard sensitive;
    private final Clock clock;
    @Autowired private ObjectMapper json;
    @Autowired private ClosingQueryService closing;
    @Autowired private ProjectBoardService board;
    @Autowired private ProjectBoardMapper boardMapper;
    @Autowired private ObjectProvider<AiNextPositionReader> nextPositions;
    public AnalysisRouter(FinancialAnalysisTools financial,WorkforceAnalysisTools workforce,ForecastAnalysisTools forecasts,
            AiSourceReads reads,AiSensitiveGuard sensitive,Clock clock) {
        this.financial=financial;this.workforce=workforce;this.forecasts=forecasts;this.reads=reads;this.sensitive=sensitive;this.clock=clock;
    }
    public Answer execute(Request request) {
        var q=request.query();sensitive.requireNonSensitive(false,false);
        if(!q.filters().devIds().isEmpty()&&q.intent()!=Intent.EXIT_AND_NEXT_POSITION)throw AiAccessGuard.forbidden();
        if(request.virtual()!=null)sensitive.requireIndependentVirtual(q.filters().references());
        validateFilters(q);
        if(Boolean.TRUE.equals(q.needsClarification()) || q.intent()==Intent.WHAT_IF_PROJECT_COST&&request.virtual()==null
                || q.intent()==Intent.PROJECT_PROFIT_TREND&&q.filters().projectIds().isEmpty()
                || q.intent()==Intent.WHAT_IF_WORKFORCE&&q.workforceAssumptions().isEmpty())
            return state("CLARIFICATION_REQUIRED",q,"대상과 기간 또는 독립 가상 조건을 구체적으로 입력해 주세요.");
        if(q.intent()==Intent.EXIT_AND_NEXT_POSITION&&nextPositions.getIfAvailable()==null)
            return state("DATA_UNAVAILABLE",q,"UI-30 비저장 추천 엔진이 아직 연결되지 않았습니다. 인력 추천 화면에서 확인해 주세요.");
        var months=months(q);var at=OffsetDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var period=new Period(q.baseYm(),months.get(0),months.get(months.size()-1),LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        List<?> rows=reads.required(()->read(request,months)).value();
        var warnings=new ArrayList<String>();var assumptions=new ArrayList<String>();
        if(q.intent()==Intent.PROJECT_PROGRESS_RISK)warnings.add("과거 스냅샷 없음 · 오늘의 현재 진행 상태입니다.");
        if(Set.of(Intent.RECEIVABLES_RISK,Intent.FORECAST_RECEIVABLES).contains(q.intent()))warnings.add("오늘 기준 실제 납기·잔액·7/30/60일 경계입니다. 통계 확률이 아닙니다. 납기 미입력 자료는 순위에서 제외됩니다.");
        if(Set.of(Intent.CASH_FORECAST,Intent.FORECAST_CASH).contains(q.intent()))warnings.add("기회 의존 입금의 식별 가능한 분류 원천이 없어 기본 시나리오만 제공합니다.");
        if(q.intent()==Intent.SALES_PIPELINE_FORECAST)assumptions.add("사업 가정: OPEN 기여 낮음 0%, 기본 원확률, 높음 100%. 확정 매출 공통 · 통계 신뢰구간 아님");
        if(request.virtual()!=null)assumptions.add("독립 가상 계산 · 업무 원천 및 공식 예측에 저장하지 않습니다.");
        if(!q.workforceAssumptions().isEmpty())assumptions.add("사용자가 선택한 수주·연장 가정 · 확정 MM과 가배정 MM 별도");
        if(Set.of(Intent.PROJECT_PROFIT_RANKING,Intent.PROJECT_PROFIT_TREND,Intent.MONTHLY_FINANCE).contains(q.intent()))warnings.add("원가 누락은 자료 없음(null)입니다. 원 단위 원천 보존, AI 비율은 둘째 자리 HALF_UP(기존 손익 화면 비율은 한 자리)");
        if(Set.of(Intent.WORKFORCE_CAPACITY,Intent.FORECAST_STAFF,Intent.WHAT_IF_WORKFORCE).contains(q.intent()))warnings.add("인력 수급 서비스 원정밀도로 계산 후 MM·비율 둘째 자리 표시 · 가배정은 확정 MM 아님");
        String closed=closeStatus(rows);String source=source(q.intent());var screen=screens(q.intent());
        var metrics=metrics(rows);var chart=chart(q.intent());
        boolean missing=rows.isEmpty()||allNumericMissing(rows);
        if(missing)warnings.add("자료 부족 · 0으로 대체하지 않았습니다.");
        var sections=new ArrayList<Section>();var errors=new ArrayList<AreaError>();
        var evaluation=new ArrayList<Evaluation>();
        if(q.intent().name().startsWith("FORECAST_")) {
            var optional=reads.optional(()->forecasts.evaluations(q));
            if("PARTIAL".equals(optional.status()))errors.add(new AreaError(q.intent(),optional.errorCode(),"공식 예측 평가 원천을 조회할 수 없습니다."));
            else if(optional.value()!=null)evaluation.addAll(optional.value());
        }
        sections.add(new Section(q.intent(),q,period,screen,source,closed,at,unit(q.intent()),metrics,rows,chart,List.copyOf(assumptions),List.copyOf(warnings),List.copyOf(evaluation)));
        String status=!errors.isEmpty()?"PARTIAL":missing?"DATA_UNAVAILABLE":"OK";
        String text=template(q,metrics,status);
        return new Answer(null,status,q,text,metrics,List.copyOf(sections),List.of(chart),List.of(new Source(source,screen,q.baseYm(),closed,at)),period,closed,
            List.copyOf(assumptions),List.copyOf(warnings),null,false,true,List.copyOf(errors));
    }
    private void validateFilters(Query q) {
        var allowed=new HashSet<>(allowedFilters(q.intent()));var f=q.filters();
        if(!f.projectIds().isEmpty()&&!allowed.contains("projectIds")||!f.customerIds().isEmpty()&&!allowed.contains("customerIds")
            ||!f.partIds().isEmpty()&&!allowed.contains("partIds")||!f.devIds().isEmpty()&&!allowed.contains("devIds")
            ||!f.jobCodes().isEmpty()&&!allowed.contains("jobCodes")||!f.gradeCodes().isEmpty()&&!allowed.contains("gradeCodes")
            ||!f.skillCodes().isEmpty()&&!allowed.contains("skillCodes"))throw AnalysisInput.invalid("filters");
    }
    private List<?> read(Request request,List<String> months) {
        var q=request.query();return switch(q.intent()) {
            case PROJECT_PROFIT_RANKING,PROJECT_PROFIT_TREND -> financial.projects(q,months);
            case MONTHLY_FINANCE -> financial.finance(q,months);
            case RECEIVABLES_RISK,FORECAST_RECEIVABLES -> financial.receivables(q);
            case PROJECT_PROGRESS_RISK -> progress(q);
            case WORKFORCE_CAPACITY,WHAT_IF_WORKFORCE,FORECAST_STAFF -> workforce.read(q,months);
            case EXIT_AND_NEXT_POSITION -> nextPositions.getObject().read(new AiNextPositionReader.Criteria(q.filters().devIds(),q.baseYm(),months.get(0),months.get(months.size()-1),q.limit()));
            case SALES_PIPELINE_FORECAST -> workforce.sales(q,months);
            case CASH_FORECAST,FORECAST_CASH -> forecasts.cash(q,months);
            case CLOSE_STATUS -> months.stream().map(m->{var c=closing.readDashboardClosing(m);return new CloseRow(m,c.status(),c.checks().stream().map(x->new Check(x.code(),x.status(),x.count())).toList());}).toList();
            case WHAT_IF_PROJECT_COST -> {
                var v=request.virtual();var result=simulate(new VirtualInput(v.revenue(),v.cost(),v.costChangePercent()));
                yield List.of(new CostRow(q.baseYm(),v.revenue(),v.cost(),result.cost(),result.profit(),result.marginPercent(),v.costChangePercent()));
            }
            case FORECAST_PROFIT -> forecasts.profit(q,months);
            case FORECAST_TURNKEY -> forecasts.turnkey(q,months);
        };
    }
    private List<ProgressRow> progress(Query q) {
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if(boardMapper.projects(today).stream().anyMatch(p->"TK".equals(p.cntrct_ty_cd())))sensitive.requireSyntheticDatabase();
        return board.readDashboard(today).rows().stream().filter(r->q.filters().projectIds().isEmpty()||q.filters().projectIds().contains(r.prj_id()))
            .map(r->new ProgressRow(r.prj_id(),r.light(),r.overdue_issue_count(),r.memo_elapsed_days(),today,true)).limit(q.limit()).toList();
    }
    public static List<String> months(Query q) {
        YearMonth base=YearMonth.parse(q.baseYm()),from=base,to=base;
        if(q.fromYm()!=null){from=YearMonth.parse(q.fromYm());to=YearMonth.parse(q.toYm());}
        else if(Set.of(Intent.SALES_PIPELINE_FORECAST,Intent.FORECAST_PROFIT,Intent.FORECAST_STAFF).contains(q.intent())){from=base.plusMonths(1);to=from.plusMonths(5);}
        else if(Set.of(Intent.CASH_FORECAST,Intent.FORECAST_CASH).contains(q.intent())){from=base.plusMonths(1);to=from.plusMonths(2);}
        else if("YTD".equals(q.periodType()))from=YearMonth.of(base.getYear(),1);
        var months=new ArrayList<String>();while(!from.isAfter(to)){months.add(from.toString());from=from.plusMonths(1);}return List.copyOf(months);
    }
    private Map<String,BigDecimal> metrics(List<?> rows) {
        var totals=new LinkedHashMap<String,BigDecimal>();var missing=new HashSet<String>();
        for(var row:rows){var n=json.valueToTree(row);for(String field:METRICS)if(n.has(field)) {
            if(n.get(field).isNull())missing.add(field);else if(n.get(field).isNumber())totals.merge(field,n.get(field).decimalValue(),BigDecimal::add);
        }}
        missing.forEach(k->totals.put(k,null));
        if(totals.containsKey("virtualProfit"))totals.put("marginPercent",ratioPercent(totals.get("virtualProfit"),totals.get("revenue")));
        else if(totals.containsKey("profit"))totals.put("marginPercent",ratioPercent(totals.get("profit"),totals.get("revenue")));
        return Collections.unmodifiableMap(totals);
    }
    private static final Set<String> METRICS=Set.of("revenue","cost","profit","operatingProfit","revenueTarget","operatingProfitTarget","receivableBalance","cashBalance","inflow","outflow","shortfall","virtualCost","virtualProfit","finalRevenue","accumulatedCost","remainingCost","supplyMm","demandMm","confirmedMm","activeTemporaryMm","idleMm");
    private boolean allNumericMissing(List<?> rows) {
        boolean any=false;for(var row:rows){var tree=json.valueToTree(row);for(String key:List.of("revenue","cost","profit","base","balance","supplyMm","virtualCost","marginBasePercent"))if(tree.has(key)){any=true;if(!tree.get(key).isNull())return false;}}
        return any;
    }
    private String closeStatus(List<?> rows){var statuses=new HashSet<String>();for(var row:rows){var t=json.valueToTree(row);if(t.has("closedStatus"))statuses.add(t.get("closedStatus").asText());}return statuses.size()>1?"MIXED":statuses.isEmpty()?"UNKNOWN":statuses.iterator().next();}
    static String template(Query q,Map<String,BigDecimal> metrics,String status) {
        var text=new StringBuilder(q.baseYm()+" · "+q.intent()+" · "+status+"\n");metrics.forEach((key,value)->text.append(key).append(": ").append(value==null?"자료 없음":value.toPlainString()).append("\n"));
        return text.append(Set.of(Intent.CASH_FORECAST,Intent.FORECAST_CASH,Intent.RECEIVABLES_RISK,Intent.FORECAST_RECEIVABLES).contains(q.intent())?"현금·미수 금액 VAT 포함":"매출·원가 VAT 제외").toString();
    }
    public static Answer state(String status,Query q,String message) {return new Answer(null,status,q,message,Map.of(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),null,"UNKNOWN",new ArrayList<>(),new ArrayList<>(),message,false,true,new ArrayList<>());}
    public static List<String> allowedFilters(Intent intent) {return switch(intent) {
        case PROJECT_PROFIT_RANKING,PROJECT_PROFIT_TREND,FORECAST_TURNKEY -> List.of("projectIds","customerIds","partIds");
        case PROJECT_PROGRESS_RISK -> List.of("projectIds");
        case RECEIVABLES_RISK,FORECAST_RECEIVABLES -> List.of("projectIds","customerIds");
        case WORKFORCE_CAPACITY,WHAT_IF_WORKFORCE,FORECAST_STAFF,SALES_PIPELINE_FORECAST -> List.of("jobCodes","gradeCodes");
        case EXIT_AND_NEXT_POSITION -> List.of("devIds");
        default -> new ArrayList<>();
    };}
    static List<String> screens(Intent i){return switch(i){case PROJECT_PROFIT_RANKING,PROJECT_PROFIT_TREND,FORECAST_TURNKEY->List.of("UI-04");case MONTHLY_FINANCE,FORECAST_PROFIT->List.of("UI-08");case RECEIVABLES_RISK,FORECAST_RECEIVABLES->List.of("UI-13");case CASH_FORECAST,FORECAST_CASH->List.of("UI-21");case CLOSE_STATUS->List.of("UI-14");case PROJECT_PROGRESS_RISK->List.of("UI-01");case EXIT_AND_NEXT_POSITION->List.of("UI-30");case WHAT_IF_PROJECT_COST->new ArrayList<>();default->List.of("UI-28");};}
    static String source(Intent i){return switch(i){case PROJECT_PROFIT_RANKING,PROJECT_PROFIT_TREND,FORECAST_TURNKEY->"BillingRevenueQuery / CostAllocationService";case MONTHLY_FINANCE,FORECAST_PROFIT->"MonthlyProfitService";case RECEIVABLES_RISK,FORECAST_RECEIVABLES->"BillingReceivableService";case CASH_FORECAST,FORECAST_CASH->"CashFlowService";case CLOSE_STATUS->"ClosingQueryService";case PROJECT_PROGRESS_RISK->"ProjectBoardService";case WHAT_IF_PROJECT_COST->"AiDataConventions.simulate";case EXIT_AND_NEXT_POSITION->"UI-30 read-only engine";default->"WorkforceForecastService";};}
    static String unit(Intent i){return Set.of(Intent.WORKFORCE_CAPACITY,Intent.FORECAST_STAFF,Intent.WHAT_IF_WORKFORCE).contains(i)?"MM":"MIXED";}
    static Chart chart(Intent i){return switch(i){case PROJECT_PROFIT_RANKING->new Chart("BAR","projectId",List.of("revenue","cost","profit"));case PROJECT_PROFIT_TREND->new Chart("LINE","month",List.of("revenue","cost","profit"));case MONTHLY_FINANCE->new Chart("LINE","month",List.of("revenue","operatingProfit"));case CASH_FORECAST,FORECAST_CASH,SALES_PIPELINE_FORECAST,FORECAST_PROFIT->new Chart("LINE","month",List.of("low","base","high"));case WORKFORCE_CAPACITY,FORECAST_STAFF,WHAT_IF_WORKFORCE->new Chart("LINE","month",List.of("supplyMm","demandMm","confirmedMm","activeTemporaryMm"));case WHAT_IF_PROJECT_COST->new Chart("BAR","month",List.of("virtualCost","virtualProfit"));default->new Chart("TABLE","projectId",new ArrayList<>());};}
}
