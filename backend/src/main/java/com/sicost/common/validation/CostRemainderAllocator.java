package com.sicost.common.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public final class CostRemainderAllocator {
    private CostRemainderAllocator() {}

    public record Weight(Long asgn_id, BigDecimal weight) {}

    // All shares have the same exact denominator; compare integer remainders, not rounded ratios.
    public static List<Long> allocate(long amount, List<Weight> weights) {
        if (amount < 0) throw BillingValues.invalid("amount");
        var ids = new HashSet<Long>();
        int scale = 0;
        for (Weight weight : weights) {
            if (!ids.add(weight.asgn_id()) || weight.weight() == null || weight.weight().signum() < 0) {
                throw BillingValues.invalid("mm_qty");
            }
            scale = Math.max(scale, weight.weight().scale());
        }
        var units = new ArrayList<BigInteger>();
        BigInteger total = BigInteger.ZERO;
        for (Weight weight : weights) {
            BigInteger unit = weight.weight().setScale(scale).unscaledValue();
            units.add(unit);
            total = total.add(unit);
        }
        if (total.signum() == 0) {
            if (amount != 0) throw BillingValues.invalid("mm_qty");
            return weights.stream().map(w -> 0L).toList();
        }
        var result = new ArrayList<Long>();
        var fractions = new ArrayList<BigInteger>();
        var order = new ArrayList<Integer>();
        long assigned = 0;
        for (int i = 0; i < weights.size(); i++) {
            BigInteger[] quotient = BigInteger.valueOf(amount).multiply(units.get(i)).divideAndRemainder(total);
            long share = quotient[0].longValueExact();
            result.add(share);
            assigned = Math.addExact(assigned, share);
            fractions.add(quotient[1]);
            if (units.get(i).signum() > 0) order.add(i);
        }
        order.sort(Comparator.<Integer, BigInteger>comparing(fractions::get).reversed()
            .thenComparing(i -> weights.get(i).asgn_id(), Comparator.nullsLast(Long::compareTo)));
        long remainder = amount - assigned;
        for (int i = 0; i < remainder; i++) {
            int index = order.get(i);
            result.set(index, Math.addExact(result.get(index), 1));
        }
        return List.copyOf(result);
    }
}
