package com.sicost.costsops;

import com.sicost.common.validation.CostRemainderAllocator;
import com.sicost.common.validation.CostRemainderAllocator.Weight;
import com.sicost.costallocation.CostAllocationCalculator;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.sicost.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;

class CostRemainderRegressionTest {
    @Test void idleWinsWhenItsRemainderIsLargerNotAlwaysLast() {
        assertThat(CostRemainderAllocator.allocate(1, List.of(
            new Weight(1L, new BigDecimal("0.01")), new Weight(null, new BigDecimal("0.99")))))
            .containsExactly(0L, 1L);
    }
    @Test void exactTieUsesAssignmentIdAndIdleLastRegardlessOfInputOrder() {
        assertThat(CostRemainderAllocator.allocate(2, List.of(
            new Weight(null, BigDecimal.ONE), new Weight(9L, BigDecimal.ONE), new Weight(2L, BigDecimal.ONE))))
            .containsExactly(0L, 1L, 1L);
    }
    @Test void nearEqualRemaindersBeyondDoublePrecisionAreNotTreatedAsTies() {
        assertThat(CostRemainderAllocator.allocate(1, List.of(
            new Weight(1L, new BigDecimal("0.5000000000000000000000000000000000000")),
            new Weight(9L, new BigDecimal("0.5000000000000000000000000000000000001")))))
            .containsExactly(0L, 1L);
    }
    @Test void zeroWeightNeverReceivesResidual() {
        assertThat(CostRemainderAllocator.allocate(1, List.of(
            new Weight(1L, BigDecimal.ZERO), new Weight(2L, BigDecimal.ONE), new Weight(null, BigDecimal.ONE))))
            .containsExactly(0L, 1L, 0L);
    }
    @ParameterizedTest @ValueSource(longs = {0, 1, 2, 100, 9007199254740993L, Long.MAX_VALUE})
    void allocationIsConservedAndEachShareIsFloorOrCeilingOfExactFraction(long amount) {
        var weights = List.of(new Weight(9L, new BigDecimal("0.33")),
            new Weight(2L, new BigDecimal("0.330")), new Weight(null, new BigDecimal("0.34")));
        var shares = CostRemainderAllocator.allocate(amount, weights);
        assertThat(shares.stream().reduce(0L, Math::addExact)).isEqualTo(amount);
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal exact = BigDecimal.valueOf(amount).multiply(weights.get(i).weight());
            long floor = exact.setScale(0, RoundingMode.FLOOR).longValueExact();
            long ceiling = exact.setScale(0, RoundingMode.CEILING).longValueExact();
            assertThat(shares.get(i)).isBetween(floor, ceiling);
        }
    }
    @Test void calculatorUsesSameRuleForLaborEveryExpenseAndTurnkeyRevenue() {
        var first = new Assignment(9, 37, 71, "TK", "RUN", "NEW", "ACTIVE", new BigDecimal("0.50"), null);
        var second = new Assignment(2, 37, 71, "TK", "RUN", "NEW", "ACTIVE", new BigDecimal("0.50"), null);
        var sources = new Sources(List.of(new Person(37, "FT", "DIRECT", 1L)), List.of(first, second),
            List.of(new Expense(81, 37L, null, null, null, 1), new Expense(82, 37L, null, null, null, 1)),
            List.of(new Turnkey(71, 37, 1L)));
        var result = CostAllocationCalculator.calculate("2026-10", sources);
        assertThat(result.items()).extracting(AllocationRow::asgn_id).containsExactly(2L, 9L);
        assertThat(result.items()).extracting(AllocationRow::lbr_cst_amt).containsExactly(1L, 0L);
        assertThat(result.items()).extracting(AllocationRow::drct_expns_amt).containsExactly(2L, 0L);
        assertThat(result.items()).extracting(AllocationRow::sls_amt).containsExactly(1L, 0L);
        var reversed = new Sources(sources.people(), List.of(second, first), sources.expenses(), sources.turnkey());
        assertThat(CostAllocationCalculator.calculate("2026-10", reversed)).isEqualTo(result);
    }
}
