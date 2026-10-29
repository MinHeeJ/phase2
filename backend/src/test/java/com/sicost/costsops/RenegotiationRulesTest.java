package com.sicost.costsops;

import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RenegotiationRulesTest {
    AllocationRow allocation(String mm, Long price, long cost) {
        return new AllocationRow("2026-10", 1L, 1L, 1L, "PROJECT", new BigDecimal(mm),
            BigDecimal.ONE, BigDecimal.ONE, cost, 0, 0, price, "N", "N", "N");
    }
    RenegotiationModels.Row row(AllocationRow allocation, String goal) {
        return RenegotiationCalculator.row(RenegotiationApiTest.source(1), allocation,
            new BigDecimal(goal), BigDecimal.valueOf(5), LocalDate.of(2026,10,29));
    }
    @Test void targetDisplayRoundingDoesNotInflateRequiredRaiseOrAnnualEffects() {
        var row = row(allocation("0.50", 1000000L, 450001), "20");
        assertThat(row.target_price_amt()).isEqualTo(1130000);
        assertThat(row.required_raise_rt()).isEqualByComparingTo("12.5");
        assertThat(row.target_annual_profit_increase_amt()).isEqualTo(750015);
        assertThat(row.assumed_annual_profit_increase_amt()).isEqualTo(300000);
        assertThat(row.days_remaining()).isEqualTo(64);
    }
    @Test void zeroMmNeverInventsRatioPriceOrTargetEffect() {
        var row = row(allocation("0.00", 1000L, 100), "20");
        assertThat(row.margin_rt()).isNull();
        assertThat(row.target_price_amt()).isNull();
        assertThat(row.required_raise_rt()).isNull();
        assertThat(row.target_annual_profit_increase_amt()).isNull();
        assertThat(row.assumed_annual_profit_increase_amt()).isZero();
    }
    @Test void zeroPriceKeepsCostAndComputableTargetButNotRatioOrRaise() {
        var row = row(allocation("1.00", 0L, 100), "20");
        assertThat(row.margin_rt()).isNull();
        assertThat(row.required_raise_rt()).isNull();
        assertThat(row.target_price_amt()).isEqualTo(10000);
        assertThat(row.target_annual_profit_increase_amt()).isEqualTo(1500);
    }
    @Test void missingPriceIsNullNotAnInventedRevenue() {
        var row = row(allocation("1.00", null, 100), "20");
        assertThat(row.margin_rt()).isNull();
        assertThat(row.assumed_annual_profit_increase_amt()).isNull();
        assertThat(row.target_annual_profit_increase_amt()).isNull();
    }
    @Test void hundredPercentGoalIsUncomputable() {
        var row = row(allocation("1.00", 1000L, 900), "100");
        assertThat(row.target_price_amt()).isNull();
        assertThat(row.required_raise_rt()).isNull();
    }
    @Test void strictFilterUsesUnroundedMarginNotDisplayedOneDecimal() {
        assertThat(RenegotiationCalculator.below(allocation("1.00", 100000L, 85001), new BigDecimal("15"))).isTrue();
        assertThat(RenegotiationCalculator.below(allocation("1.00", 100000L, 85000), new BigDecimal("15"))).isFalse();
    }
}
