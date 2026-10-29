package com.sicost.costsops;

import com.sicost.common.costallocation.CostAllocationModels.Allocation;
import java.math.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import static com.sicost.costsops.RenegotiationModels.*;

public final class RenegotiationCalculator {
    private RenegotiationCalculator() {}
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWELVE = new BigDecimal("12");
    public static BigDecimal revenue(Row row) {
        return row.mm_qty() == null || row.bill_untprc_amt() == null ? null
            : row.mm_qty().multiply(BigDecimal.valueOf(row.bill_untprc_amt()));
    }
    public static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() <= 0 || numerator == null ? null
            : numerator.multiply(HUNDRED).divide(denominator, MathContext.DECIMAL128);
    }
    public static Row calculate(Candidate c, Allocation a, BigDecimal raiseRate, LocalDate today, boolean closed) {
        BigDecimal mm = a == null ? null : a.mm_qty();
        Long price = closed ? (a == null ? null : a.bill_untprc_amt()) : c.bill_untprc_amt();
        Long labor = a == null ? null : a.lbr_cst_amt();
        Long expense = a == null ? null : a.drct_expns_amt();
        BigDecimal cost = labor == null || expense == null ? null
            : BigDecimal.valueOf(labor).add(BigDecimal.valueOf(expense));
        BigDecimal sales = mm == null || price == null ? null : mm.multiply(BigDecimal.valueOf(price));
        BigDecimal margin = cost == null || sales == null ? null : percentage(sales.subtract(cost), sales);
        BigDecimal rawTarget = cost == null || mm == null || mm.signum() <= 0 ? null
            : cost.divide(mm.multiply(new BigDecimal("0.8")), MathContext.DECIMAL128);
        BigDecimal target = rawTarget == null ? null
            : rawTarget.divide(new BigDecimal("10000"), 0, RoundingMode.CEILING).multiply(new BigDecimal("10000"));
        BigDecimal required = rawTarget == null || price == null ? null
            : percentage(rawTarget.subtract(BigDecimal.valueOf(price)), BigDecimal.valueOf(price));
        BigDecimal increase = sales == null || mm == null || mm.signum() <= 0 || price == null || price <= 0 ? null
            : sales.multiply(raiseRate).divide(HUNDRED).multiply(TWELVE).setScale(0, RoundingMode.DOWN);
        BigDecimal targetIncrease = rawTarget == null || price == null || price <= 0 ? null
            : cost.divide(new BigDecimal("0.8")).subtract(sales).multiply(TWELVE).setScale(0, RoundingMode.DOWN);
        return new Row(c.asgn_id(), c.dev_id(), c.dev_nm(), c.emp_se_cd(), c.prj_id(), c.prj_nm(), c.end_dt(),
            ChronoUnit.DAYS.between(today, c.end_dt()), mm, price, labor, expense, cost, margin,
            target, required, increase, targetIncrease);
    }
}
