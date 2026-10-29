package com.sicost.costsops;

import com.sicost.common.costs.CostAllocationModels.Allocation;
import java.math.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.sicost.costsops.RenegotiationModels.*;

public final class RenegotiationCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private RenegotiationCalculator() {}
    public static Row row(Candidate person, Allocation allocation, BigDecimal target,
            BigDecimal raise, LocalDate today, boolean missingSalary) {
        List<String> warnings = new ArrayList<>();
        BigDecimal mm = allocation == null ? BigDecimal.ZERO.setScale(2) : allocation.mm_qty();
        if (mm == null) mm = BigDecimal.ZERO.setScale(2);
        Long price = allocation == null ? null : allocation.bill_untprc_amt();
        Long cost = allocation == null || missingSalary ? null
            : Math.addExact(allocation.lbr_cst_amt(), allocation.drct_expns_amt());
        if (missingSalary) warnings.add("급여 미업로드 — 이익률·목표 단가 계산 불가");
        if (allocation == null || mm.signum() == 0) warnings.add("MM 없음 — 목표 단가 계산 불가");
        if (price == null) warnings.add("단가 미등록");
        if (price != null && price == 0) warnings.add("단가 0 — 이익률·필요 인상률 계산 불가");
        BigDecimal revenue = price == null ? null : BigDecimal.valueOf(price).multiply(mm);
        BigDecimal margin = cost == null || revenue == null ? null
            : ratio(revenue.subtract(BigDecimal.valueOf(cost)), revenue);
        BigDecimal targetPrice = null, required = null, targetEffect = null;
        BigDecimal targetDenominator = mm.multiply(HUNDRED.subtract(target));
        if (cost != null && targetDenominator.signum() > 0) {
            BigDecimal targetNumerator = BigDecimal.valueOf(cost).multiply(HUNDRED);
            targetPrice = targetNumerator.divide(targetDenominator.multiply(BigDecimal.valueOf(10000)),
                0, RoundingMode.CEILING).multiply(BigDecimal.valueOf(10000));
            if (price != null) {
                BigDecimal difference = targetNumerator.subtract(targetDenominator.multiply(BigDecimal.valueOf(price)));
                required = ratio(difference, targetDenominator.multiply(BigDecimal.valueOf(price)));
                targetEffect = difference.max(BigDecimal.ZERO).multiply(mm).multiply(BigDecimal.valueOf(12))
                    .divide(targetDenominator, 0, RoundingMode.FLOOR);
            }
        } else if (target.compareTo(HUNDRED) >= 0) warnings.add("목표 이익률 100% — 목표 단가 계산 불가");
        BigDecimal effect = revenue == null ? null : revenue.multiply(raise).multiply(BigDecimal.valueOf(12))
            .divide(HUNDRED, 0, RoundingMode.FLOOR);
        return new Row(person.asgn_id(), person.dev_id(), person.dev_nm(), person.emp_se_cd(),
            person.prj_id(), person.prj_nm(), person.cntrct_ty_cd(), person.end_dt(),
            ChronoUnit.DAYS.between(today, person.end_dt()), mm, price, cost, margin, targetPrice,
            required, effect, targetEffect, List.copyOf(warnings));
    }
    static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? null
            : numerator.multiply(HUNDRED).divide(denominator, 1, RoundingMode.HALF_UP);
    }
    static boolean below(Row row, BigDecimal threshold) {
        if (row.bill_untprc_amt() == null || row.allocated_cost_amt() == null) return true;
        BigDecimal revenue = BigDecimal.valueOf(row.bill_untprc_amt()).multiply(row.mm_qty());
        return revenue.signum() == 0 || revenue.subtract(BigDecimal.valueOf(row.allocated_cost_amt()))
            .multiply(HUNDRED).compareTo(revenue.multiply(threshold)) < 0;
    }
    static Summary summary(List<Row> rows) {
        BigDecimal revenue = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        BigDecimal effect = BigDecimal.ZERO, targetEffect = BigDecimal.ZERO;
        for (Row row : rows) {
            if (row.margin_rt() != null) {
                revenue = revenue.add(BigDecimal.valueOf(row.bill_untprc_amt()).multiply(row.mm_qty()));
                cost = cost.add(BigDecimal.valueOf(row.allocated_cost_amt()));
            }
            if (row.annual_increase_amt() != null) effect = effect.add(row.annual_increase_amt());
            if (row.target_annual_increase_amt() != null) targetEffect = targetEffect.add(row.target_annual_increase_amt());
        }
        return new Summary(rows.stream().map(Row::dev_id).distinct().count(),
            ratio(revenue.subtract(cost), revenue), effect, targetEffect);
    }
}
