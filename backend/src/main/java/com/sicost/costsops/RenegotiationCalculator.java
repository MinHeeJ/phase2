package com.sicost.costsops;

import com.sicost.common.validation.CostValues;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import java.math.BigDecimal;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import static com.sicost.costsops.RenegotiationModels.*;

public final class RenegotiationCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private RenegotiationCalculator() {}

    public static BigDecimal revenue(AllocationRow allocation) {
        if (allocation.bill_untprc_amt() == null || allocation.mm_qty() == null) return null;
        return BigDecimal.valueOf(allocation.bill_untprc_amt()).multiply(allocation.mm_qty());
    }

    public static boolean below(AllocationRow allocation, BigDecimal threshold) {
        BigDecimal revenue = revenue(allocation);
        // Keep missing sources visible with their specific warnings and null ratios.
        return revenue == null || revenue.signum() == 0 || revenue.subtract(BigDecimal.valueOf(allocation.cost()))
            .multiply(HUNDRED).compareTo(revenue.multiply(threshold)) < 0;
    }

    public static Row row(Source source, AllocationRow allocation, BigDecimal goal,
            BigDecimal raise, LocalDate today) {
        BigDecimal revenue = revenue(allocation);
        BigDecimal cost = BigDecimal.valueOf(allocation.cost());
        BigDecimal margin = CostValues.percent(revenue == null ? null : revenue.subtract(cost), revenue);
        BigDecimal mm = allocation.mm_qty();
        Long price = allocation.bill_untprc_amt();
        Long target = null, annualTarget = null, annualAssumed = null;
        BigDecimal required = null;
        if (revenue != null) {
            annualAssumed = CostValues.signedWon(revenue.multiply(raise).multiply(TWELVE).divide(HUNDRED));
        }
        if (mm != null && mm.signum() > 0 && goal != null && goal.signum() >= 0
                && goal.compareTo(HUNDRED) < 0) {
            // Only the displayed price is rounded to 10,000 won. Annual effects do not use it.
            BigDecimal denominator = mm.multiply(HUNDRED.subtract(goal));
            BigDecimal numerator = cost.multiply(HUNDRED);
            target = numerator.divide(denominator.multiply(BigDecimal.valueOf(10000)), 0, RoundingMode.CEILING)
                .multiply(BigDecimal.valueOf(10000)).longValueExact();
            if (price != null && price > 0) {
                required = numerator.subtract(denominator.multiply(BigDecimal.valueOf(price)))
                    .multiply(HUNDRED).divide(denominator.multiply(BigDecimal.valueOf(price)), 1, RoundingMode.HALF_UP);
            }
            if (revenue != null) {
                BigDecimal increaseNumerator = numerator.subtract(revenue.multiply(HUNDRED.subtract(goal))).max(BigDecimal.ZERO);
                annualTarget = increaseNumerator.multiply(TWELVE)
                    .divide(HUNDRED.subtract(goal), 0, RoundingMode.DOWN).longValueExact();
            }
        }
        return new Row(source.asgn_id(), source.dev_id(), source.dev_nm(), source.emp_se_cd(),
            source.prj_id(), source.prj_nm(), source.end_dt(), ChronoUnit.DAYS.between(today, source.end_dt()),
            mm, price, allocation.cost(), margin, target, required, annualAssumed, annualTarget);
    }
}
