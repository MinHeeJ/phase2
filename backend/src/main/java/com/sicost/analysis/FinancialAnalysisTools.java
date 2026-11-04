package com.sicost.analysis;

import com.sicost.billingsops.*;
import com.sicost.common.ai.*;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.profit.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static com.sicost.analysis.AnalysisModels.*;
import static com.sicost.common.ai.AiDataConventions.*;

@Service
public class FinancialAnalysisTools {
    private final AnalysisMapper mapper;
    private final BillingRevenueQuery billing;
    private final CostAllocationService costs;
    private final MonthlyProfitService profit;
    private final MonthlyProfitMapper profits;
    private final AiSensitiveGuard sensitive;
    @Autowired private BillingMapper bills;
    @Autowired private BillingReceivableService receivables;
    @Autowired private Clock clock;
    public FinancialAnalysisTools(AnalysisMapper mapper,BillingRevenueQuery billing,CostAllocationService costs,
            MonthlyProfitService profit,MonthlyProfitMapper profits,AiSensitiveGuard sensitive) {
        this.mapper=mapper;this.billing=billing;this.costs=costs;this.profit=profit;this.profits=profits;this.sensitive=sensitive;
    }
    public String status(String month) { return "CLOSED".equals(mapper.closeStatus(month))?"CLOSED":"OPEN"; }
    public List<ProfitRow> projects(Query query,List<String> months) {
        var projects=mapper.projects(query.filters());var rows=new ArrayList<ProfitRow>();
        var revenues=new HashMap<Integer,List<BillingModels.ProjectRevenue>>();
        for(String month:months) {
            boolean closed="CLOSED".equals(status(month));
            var snapshot=closed?mapper.closedCosts(month):new ArrayList<AnalysisMapper.ProjectCost>();
            boolean hasPayroll=!closed&&mapper.hasPayroll(month);
            var allocation=hasPayroll?costs.calculate(month,null):null;
            var revenue=revenues.computeIfAbsent(YearMonth.parse(month).getYear(),y->billing.projectRevenue(new BillingModels.CostRevenueQuery(y,null)));
            for(var p:projects) {
                var sales=revenue.stream().filter(r->r.prj_id()==p.id()&&r.month().equals(month)).toList();
                BigDecimal amount=sales.isEmpty()?null:sales.stream().map(r->BigDecimal.valueOf(r.confirmed_supply_amt())).reduce(BigDecimal.ZERO,BigDecimal::add);
                BigDecimal cost=null;
                if(closed) {
                    var saved=snapshot.stream().filter(c->c.projectId()==p.id()).findFirst().orElse(null);
                    if(saved!=null)cost=saved.amount();
                } else if(allocation!=null) {
                    var selected=allocation.items().stream().filter(r->Objects.equals(r.prj_id(),p.id())).toList();
                    boolean missing=allocation.warnings().stream().anyMatch(w->w.startsWith("급여 미업로드:"));
                    if(!selected.isEmpty()&&!missing)cost=selected.stream().map(r->BigDecimal.valueOf(r.cost())).reduce(BigDecimal.ZERO,BigDecimal::add);
                }
                BigDecimal result=amount==null||cost==null?null:amount.subtract(cost);
                rows.add(new ProfitRow(p.id(),p.name(),month,amount,cost,result,ratioPercent(result,amount),closed?"CLOSED":"OPEN"));
            }
        }
        if("YTD".equals(query.periodType())&&query.intent()==Intent.PROJECT_PROFIT_RANKING) {
            var annual=new ArrayList<ProfitRow>();for(var p:projects){var own=rows.stream().filter(r->r.projectId()==p.id()).toList();
                BigDecimal revenue=sum(own.stream().map(ProfitRow::revenue).toList()),cost=sum(own.stream().map(ProfitRow::cost).toList());
                BigDecimal result=revenue==null||cost==null?null:revenue.subtract(cost);
                annual.add(new ProfitRow(p.id(),p.name(),query.baseYm(),revenue,cost,result,ratioPercent(result,revenue),own.stream().allMatch(r->"CLOSED".equals(r.closedStatus()))?"CLOSED":"OPEN"));}
            rows=annual;
        }
        if(query.intent()==Intent.PROJECT_PROFIT_RANKING) {
            Comparator<BigDecimal> value=query.sort().endsWith("ASC")?Comparator.naturalOrder():Comparator.reverseOrder();
            Comparator<ProfitRow> order=Comparator.comparing(r->query.sort().startsWith("MARGIN")?r.marginPercent():r.profit(),Comparator.nullsLast(value));
            return rows.stream().sorted(order.thenComparingLong(ProfitRow::projectId)).limit(query.limit()).toList();
        }
        return List.copyOf(rows);
    }
    public List<FinanceRow> finance(Query query,List<String> months) {
        if(!query.filters().projectIds().isEmpty()||!query.filters().customerIds().isEmpty()||!query.filters().partIds().isEmpty())throw AnalysisInput.invalid("filters");
        var result=new ArrayList<FinanceRow>();var years=new HashMap<Integer,List<Map<String,Object>>>();
        for(String month:months) {
            var state=status(month);Map<String,Object> row;
            if("CLOSED".equals(state))row=profits.snapshot(new MonthlyProfitModels.SnapshotKey(month,"CLOSE"));
            else if(mapper.hasPayroll(month)) {row=years.computeIfAbsent(YearMonth.parse(month).getYear(),profit::calculate).stream().filter(r->month.equals(r.get("month"))).findFirst().orElse(null);}
            else {row=new LinkedHashMap<>();var sales=billing.revenue(YearMonth.parse(month).getYear()).stream().filter(r->month.equals(r.month())).findFirst().orElse(null);row.put("revenue",sales==null?null:sales.confirmed_supply_amt());}
            var target=mapper.target(month);
            result.add(new FinanceRow(month,null,number(row,"revenue"),number(row,"operating_profit"),target==null?null:target.revenue(),target==null?null:target.profit(),state));
        }
        return List.copyOf(result);
    }
    public List<ReceivableRow> receivables(Query query) {
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var cutoff=new BillingModels.ReceivableCutoff(today,LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        var result=new ArrayList<ReceivableRow>();
        for(var state:bills.statesThrough(cutoff)) {
            if(!"CONFIRM".equals(state.bill_stts_cd())||"Y".equals(state.amnd_yn()))continue;
            if(!query.filters().projectIds().isEmpty()&&!query.filters().projectIds().contains(state.prj_id()))continue;
            if(!query.filters().customerIds().isEmpty()&&!query.filters().customerIds().contains(state.customer_id()))continue;
            var row=receivables.row(state);if(row.receivable_amt()<=0||row.rcpt_plan_dt()==null)continue;
            int days=Math.max(0,receivables.days(row.rcpt_plan_dt()));
            result.add(new ReceivableRow(row.prj_id(),row.customer_id(),row.rcpt_plan_dt(),BigDecimal.valueOf(row.receivable_amt()),days,days>=60?60:days>=30?30:days>=7?7:null,null,today));
        }
        return result.stream().sorted(Comparator.comparingInt(ReceivableRow::overdueDays).reversed().thenComparing(ReceivableRow::balance,Comparator.reverseOrder()).thenComparingLong(ReceivableRow::projectId)).limit(query.limit()).toList();
    }
    static BigDecimal sum(List<BigDecimal> values) {
        if(values.isEmpty()||values.stream().anyMatch(Objects::isNull))return null;
        return values.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    static BigDecimal number(Map<String,Object> map,String key){return map==null||map.get(key)==null?null:new BigDecimal(map.get(key).toString());}
}
