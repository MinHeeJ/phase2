package com.sicost.billingsops;

import com.sicost.common.security.*;
import com.sicost.common.validation.*;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly=true)
public class BillingRevenueQuery {
    private final BillingMapper mapper;
    private final BillingReceivableService receivables;
    private final Clock clock;
    private final PermissionService permissions;
    public BillingRevenueQuery(BillingMapper mapper,BillingReceivableService receivables,Clock clock,PermissionService permissions) {
        this.mapper=mapper;this.receivables=receivables;this.clock=clock;this.permissions=permissions;
    }
    public List<Revenue> revenue(int year) {return mapper.revenue(BillingValues.year(year,clock));}
    public List<ProjectRevenue> projectRevenue(CostRevenueQuery query) {
        if (query == null) throw BillingValues.invalid("year");
        int year = CostValues.year(query.year(), clock);
        if (query.projectId() != null) CostValues.id(query.projectId(), "projectId");
        return mapper.projectRevenue(new CostRevenueQuery(year, query.projectId()));
    }
    public List<Cash> cash(int year) {return mapper.cash(BillingValues.year(year,clock));}
    public Summary summary(Integer requested) {
        permissions.can(BillingActions.READ);
        int year=BillingValues.year(requested,clock);var revenue=revenue(year);var cash=cash(year);
        String month=YearMonth.from(ProjectValues.today(clock)).toString();
        long annual=0,current=0,received=0,remaining=0,overdue=0,planned=mapper.planned(year),draft=0;
        for(var r:revenue) {annual=BillingValues.add(annual,r.confirmed_supply_amt(),"sply_amt");if(r.month().equals(month))current=r.confirmed_supply_amt();}
        for(var r:cash)received=BillingValues.add(received,r.rcpt_amt(),"rcpt_amt");
        for(State s:mapper.states(year)) {
            if("DRAFT".equals(s.bill_stts_cd()))draft=BillingValues.add(draft,s.sply_amt(),"sply_amt");
            if("PLAN".equals(s.bill_stts_cd()))planned=BillingValues.add(planned,s.sply_amt(),"sply_amt");
            Row row=receivables.row(s);remaining=BillingValues.add(remaining,row.receivable_amt(),"receivable_amt");
            Integer days=receivables.days(s.rcpt_plan_dt());
            if(days!=null && days>0)overdue=BillingValues.add(overdue,row.receivable_amt(),"overdue_amt");
        }
        return new Summary(year,annual,current,remaining,overdue,received,planned,draft,revenue,cash);
    }
}
