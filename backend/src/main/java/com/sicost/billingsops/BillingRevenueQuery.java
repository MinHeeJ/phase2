package com.sicost.billingsops;

import com.sicost.common.validation.BillingRules;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingRevenueQuery {
    private final BillingMapper mapper;
    private final BillingReceivableService receivables;
    public BillingRevenueQuery(BillingMapper mapper, BillingReceivableService receivables) {
        this.mapper = mapper;
        this.receivables = receivables;
    }
    public List<RevenueMonth> revenue(int year) {
        Map<String, Long> months = new TreeMap<>();
        for (Bill bill : mapper.bills()) {
            if (!"CONFIRM".equals(bill.bill_stts_cd())) continue;
            var p = mapper.project(bill.prj_id());
            YearMonth month = BillingRules.revenueMonth(p.contract_type(), bill.atrb_ym(), bill.bill_dt());
            if (month.getYear() == year) months.merge(month.toString(), bill.sply_amt(),
                (a, b) -> BillingRules.add(a, b, "confirmed_supply_amt"));
        }
        return months.entrySet().stream().map(e -> new RevenueMonth(e.getKey(), e.getValue())).toList();
    }
    public Summary summary(Integer requested) {
        int year = requested == null ? receivables.today().getYear() : requested;
        if (year < 1 || year > 9999) throw BillingRules.invalid("year");
        var months = revenue(year);
        var cash = mapper.cash(year);
        var overdue = receivables.all(new OverdueFilter(year, null, null, null, null));
        long planned = 0, draft = 0;
        for (Bill b : mapper.bills()) {
            String month = "TK".equals(mapper.project(b.prj_id()).contract_type())
                ? b.bill_dt() == null ? null : YearMonth.from(b.bill_dt()).toString() : b.atrb_ym();
            if (month == null || !month.startsWith(year + "-")) continue;
            if ("PLAN".equals(b.bill_stts_cd())) planned = BillingRules.add(planned, b.sply_amt(), "planned_supply_amt");
            if ("DRAFT".equals(b.bill_stts_cd())) draft = BillingRules.add(draft, b.sply_amt(), "draft_supply_amt");
        }
        String current = YearMonth.from(receivables.today()).toString();
        return new Summary(year, sum(months.stream().map(RevenueMonth::confirmed_supply_amt).toList()),
            sum(months.stream().filter(m -> current.equals(m.month())).map(RevenueMonth::confirmed_supply_amt).toList()),
            sum(overdue.stream().map(r -> r.billing().receivable_amt()).toList()),
            sum(overdue.stream().filter(r -> r.overdue_days() != null && r.overdue_days() > 0).map(r -> r.billing().receivable_amt()).toList()),
            sum(cash.stream().map(CashDate::rcpt_amt).toList()), planned, draft, months, cash);
    }
    private long sum(List<Long> values) {
        return values.stream().reduce(0L, (a, b) -> BillingRules.add(a, b, "amount"));
    }
}
