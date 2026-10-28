package com.sicost.billingsops;

import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billingsops.BillingModels.*;

@Service
@Transactional(readOnly = true)
public class BillingRevenueQuery {
    private final BillingMapper mapper;
    private final BillingReceivableService receivables;
    private final Clock clock;
    public BillingRevenueQuery(BillingMapper mapper, BillingReceivableService receivables, Clock clock) {
        this.mapper = mapper;
        this.receivables = receivables;
        this.clock = clock;
    }
    public List<Revenue> supply(int year) { return mapper.revenue(year); }
    public List<Cash> cash(int year) { return mapper.cash(year); }
    public Summary summary(Integer requested) {
        int year = BillingQuery.year(requested, clock);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var revenue = supply(year);
        var cash = cash(year);
        var rows = mapper.bills().stream().filter(b -> {
            String month = BillingReceivableService.month(b);
            return month != null && month.startsWith(year + "-");
        }).toList();
        var balances = rows.stream().filter(b -> b.orgnl_bill_id() == null && "CONFIRM".equals(b.bill_stts_cd()))
            .map(receivables::row).toList();
        return new Summary(year, BillingAmountService.sum(revenue.stream().map(Revenue::confirmed_supply_amt).toList()),
            BillingAmountService.sum(revenue.stream().filter(r -> r.month().equals(YearMonth.from(today).toString()))
                .map(Revenue::confirmed_supply_amt).toList()),
            BillingAmountService.sum(balances.stream().map(BillingRow::receivable_amt).toList()),
            BillingAmountService.sum(balances.stream().filter(b -> b.rcpt_plan_dt() != null && b.rcpt_plan_dt().isBefore(today))
                .map(BillingRow::receivable_amt).toList()),
            BillingAmountService.sum(cash.stream().map(Cash::rcpt_amt).toList()),
            Math.addExact(mapper.virtualPlanned(year), BillingAmountService.sum(rows.stream()
                .filter(b -> "PLAN".equals(b.bill_stts_cd())).map(Bill::sply_amt).toList())),
            BillingAmountService.sum(rows.stream().filter(b -> "DRAFT".equals(b.bill_stts_cd())).map(Bill::sply_amt).toList()),
            revenue, cash);
    }
}
