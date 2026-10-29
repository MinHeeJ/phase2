package com.sicost.billingsops;

import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.billing.BillingSupportModels.Period;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingReviewModels.*;

@Service
@Transactional(readOnly=true)
public class BillingReviewService {
    private final BillingReviewMapper mapper;
    private final BillingPreviewService previews;
    private final BillingGapService gaps;
    private final BillingReceivableService receivables;
    private final BillingRevenueQuery revenue;
    private final Clock clock;
    public BillingReviewService(BillingReviewMapper mapper,BillingPreviewService previews,
            BillingGapService gaps,BillingReceivableService receivables,BillingRevenueQuery revenue,Clock clock) {
        this.mapper=mapper; this.previews=previews; this.gaps=gaps;
        this.receivables=receivables; this.revenue=revenue; this.clock=clock;
    }
    int year(Map<String,String> query) {
        int year=BillingReviewInput.integer(query,"year",ProjectValues.today(clock).getYear());
        if(year<1 || year>9999) throw BillingRules.invalid("year");
        return year;
    }
    private YearRange range(int year) { return new YearRange(String.format("%04d-01",year),String.format("%04d-12",year)); }
    public GapResult gaps(Map<String,String> query) {
        String month=query.get("month");
        if(month==null) { month=mapper.lastClosed(); if(month==null) month=YearMonth.from(ProjectValues.today(clock)).toString(); }
        BillingRules.month(month,"month");
        var paging=BillingReviewInput.paging(query,Set.of("project_name","project_id","basis_supply_amt",
            "confirmed_supply_amt","difference_amt","judgment","month"),"project_name");
        String judgment=query.get("judgment");
        if(judgment!=null && !Set.of("맞음","맞음(무상)","적게 청구","많이 청구","청구 없음","근태 미입력").contains(judgment)) throw BillingRules.invalid("judgment");
        var rows=new ArrayList<GapRow>();
        for(var project:mapper.gapProjects(month)) {
            var key=new PreviewKey(project.project_id(),month,null);
            var preview=previews.preview(key); var sources=mapper.sources(key);
            boolean attendance=preview.warnings().stream().anyMatch(w->w.contains("근태 미입력"));
            boolean free=!sources.isEmpty() && sources.stream().allMatch(s->"FREE".equals(s.kind()));
            var comparison=gaps.compare(new ProjectMonth(project.project_id(),month),preview.sply_amt(),attendance,free);
            var ids=mapper.confirmedIds(new ProjectMonth(project.project_id(),month));
            if(!attendance && !free && comparison.basis_supply_amt()==0 && comparison.confirmed_supply_amt()==0) continue;
            var suspected=new ArrayList<Suspect>();
            if(comparison.difference_amt()>0) {
                for(var source:sources) {
                    if(!"NEW".equals(source.kind()) || !YearMonth.from(source.bgn_dt()).toString().equals(month)) continue;
                    long amount=preview.details().stream().filter(l->Objects.equals(l.asgn_id(),source.asgn_id()))
                        .mapToLong(Line::bill_amt).sum();
                    if(BigDecimal.valueOf(comparison.difference_amt()).subtract(BigDecimal.valueOf(amount)).abs()
                        .compareTo(comparison.tolerance())<=0) suspected.add(new Suspect(source.display_name(),source.management_number()));
                }
            }
            String explanation=String.join(" ",preview.warnings());
            if(!suspected.isEmpty()) explanation+=" 신규 투입 누락 가능성이 있습니다.";
            var row=new GapRow(project.project_id(),project.project_name(),month,comparison.basis_supply_amt(),
                comparison.confirmed_supply_amt(),comparison.difference_amt(),comparison.tolerance(),comparison.judgment(),
                explanation,attendance,List.copyOf(suspected),ids);
            if(judgment!=null && !judgment.equals(row.judgment())) continue;
            if(!matches(query.get("q"),row.project_name())) continue;
            rows.add(row);
        }
        Comparator<GapRow> order=switch(paging.field()) {
            case "project_id" -> Comparator.comparingLong(GapRow::project_id);
            case "basis_supply_amt" -> Comparator.comparingLong(GapRow::basis_supply_amt);
            case "confirmed_supply_amt" -> Comparator.comparingLong(GapRow::confirmed_supply_amt);
            case "difference_amt" -> Comparator.comparingLong(GapRow::difference_amt);
            case "judgment" -> Comparator.comparing(GapRow::judgment);
            case "month" -> Comparator.comparing(GapRow::month);
            default -> Comparator.comparing(GapRow::project_name);
        };
        rows.sort((paging.descending()?order.reversed():order).thenComparingLong(GapRow::project_id));
        long difference=0; for(var r:rows) difference=BillingRules.add(difference,r.difference_amt(),"difference_amt");
        return new GapResult(month,difference,paging.page(rows),rows.size(),paging.page(),paging.size());
    }
    private boolean matches(String q,String text) { return q==null || text!=null && text.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)); }
    public OverdueResult overdue(Map<String,String> query) {
        var paging=BillingReviewInput.paging(query,Set.of("bill_id","bill_no","project_name","customer_name",
            "receivable_amt","rcpt_plan_dt","overdue_days","stage","next_notification_date"),"bill_id");
        Integer stage=query.containsKey("stage")?BillingReviewInput.integer(query,"stage",0):null;
        if(stage!=null && (stage<0||stage>3)) throw BillingRules.invalid("stage");
        Integer over=query.containsKey("overDays")?BillingReviewInput.integer(query,"overDays",0):null;
        if(over!=null && !Set.of(30,60,90).contains(over)) throw BillingRules.invalid("overDays");
        Long customer=query.containsKey("customerId")?BillingReviewInput.id(query.get("customerId"),"customerId"):null;
        var rows=new ArrayList<OverdueRow>();
        for(var bill:mapper.originals(range(year(query)))) {
            if(bill.receivable_amt()<=0) continue;
            var due=bill.rcpt_plan_dt();
            Long days=due==null?null:ChronoUnit.DAYS.between(due,ProjectValues.today(clock));
            Integer current=receivables.reminderStage(due,bill.receivable_amt());
            if(stage!=null && !stage.equals(current) || over!=null && (days==null || days<=over)) continue;
            if(customer!=null && !customer.equals(bill.customer_id())) continue;
            if(!matches(query.get("q"),bill.project_name()) && !matches(query.get("q"),bill.customer_name())
                && !matches(query.get("q"),bill.bill_no())) continue;
            String month=BillingRules.revenueMonth(bill.contract_type(),bill.atrb_ym(),bill.bill_dt()).toString();
            var part=mapper.historicalPart(new ProjectMonth(bill.prj_id(),month));
            var roles=new LinkedHashSet<String>();
            if(current!=null && current>=1) { roles.add("영업 담당자"); roles.add("PM"); }
            if(current!=null && current>=2) roles.add(part==null || part.name()==null ? "경영진" : "Y".equals(part.hq_yn())?"본사 영업 책임자":"영업 파트장");
            if(current!=null && current>=3) { roles.add("경영진"); roles.add("재무"); }
            rows.add(new OverdueRow(bill,days,current,due==null?"예정일 미입력":days<0?"예정":days==0?"당일":"연체",
                List.copyOf(roles),receivables.nextNotificationDate(due,bill.receivable_amt()),List.of(),part==null?null:part.name()));
        }
        Comparator<OverdueRow> order=switch(paging.field()) {
            case "bill_no" -> Comparator.comparing(r->r.billing().bill_no());
            case "project_name" -> Comparator.comparing(r->r.billing().project_name());
            case "customer_name" -> Comparator.comparing(r->r.billing().customer_name(),Comparator.nullsLast(Comparator.naturalOrder()));
            case "receivable_amt" -> Comparator.comparingLong(r->r.billing().receivable_amt());
            case "rcpt_plan_dt" -> Comparator.comparing(r->r.billing().rcpt_plan_dt(),Comparator.nullsLast(Comparator.naturalOrder()));
            case "overdue_days" -> Comparator.comparing(OverdueRow::overdue_days,Comparator.nullsLast(Comparator.naturalOrder()));
            case "stage" -> Comparator.comparing(OverdueRow::stage,Comparator.nullsLast(Comparator.naturalOrder()));
            case "next_notification_date" -> Comparator.comparing(OverdueRow::next_notification_date,Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparingLong(r->r.billing().bill_id());
        };
        rows.sort((paging.descending()?order.reversed():order).thenComparingLong(r->r.billing().bill_id()));
        var totals=new LinkedHashMap<Long,CustomerTotal>();
        for(var row:rows) {
            var b=row.billing(); var old=totals.get(b.customer_id());
            totals.put(b.customer_id(),new CustomerTotal(b.customer_id(),b.customer_name(),
                BillingRules.add(old==null?0:old.receivable_amt(),b.receivable_amt(),"receivable_amt")));
        }
        var stages=new ArrayList<StageCount>();
        for(int s=0;s<=3;s++) { int selected=s; stages.add(new StageCount(s,rows.stream().filter(r->Objects.equals(r.stage(),selected)).count())); }
        return new OverdueResult(paging.page(rows),List.copyOf(totals.values()),List.copyOf(stages),rows.size(),paging.page(),paging.size());
    }
    public Summary summary(Map<String,String> query) {
        int year=year(query); var range=range(year); var period=new Period(range.from(),range.to());
        var monthly=revenue.monthlyRevenue(period); var cash=revenue.cashReceipts(period);
        long annual=0,current=0,receivable=0,overdue=0,cashSum=0;
        String now=YearMonth.from(ProjectValues.today(clock)).toString();
        for(var r:monthly) { annual=BillingRules.add(annual,r.confirmed_supply_amt(),"sply_amt"); if(r.month().equals(now)) current=r.confirmed_supply_amt(); }
        for(var b:mapper.originals(range)) {
            receivable=BillingRules.add(receivable,b.receivable_amt(),"receivable_amt");
            if(b.rcpt_plan_dt()!=null && b.rcpt_plan_dt().isBefore(ProjectValues.today(clock))) overdue=BillingRules.add(overdue,b.receivable_amt(),"overdue_amt");
        }
        var dates=new TreeMap<LocalDate,Long>();
        for(var r:cash) { cashSum=BillingRules.add(cashSum,r.rcpt_amt(),"rcpt_amt"); dates.merge(r.rcpt_dt(),r.rcpt_amt(),(a,b)->BillingRules.add(a,b,"rcpt_amt")); }
        return new Summary(year,annual,current,receivable,overdue,cashSum,mapper.plannedSupply(range),mapper.draftSupply(range),
            monthly,dates.entrySet().stream().map(e->new CashDate(e.getKey(),e.getValue())).toList());
    }
}
