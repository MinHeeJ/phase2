package com.sicost.costsops;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static com.sicost.costsops.RenegotiationApiTest.*;
import static com.sicost.costsops.RenegotiationCalculator.*;
import static org.assertj.core.api.Assertions.*;

class RenegotiationCalculatorTest {
    private final LocalDate today = LocalDate.parse("2026-10-29");
    @Test void effectsUseUnroundedTargetNotTenThousandWonDisplayCeiling() {
        var row = calculate(candidate(17, 7, 100), allocation(17, 7, "1.00", 90L, 0), new BigDecimal("5"), today, false);
        assertThat(row.margin_rt()).isEqualByComparingTo("10");
        assertThat(row.target_price_amt()).isEqualByComparingTo("10000");
        assertThat(row.required_raise_rt()).isEqualByComparingTo("12.5");
        assertThat(row.annual_profit_increase_amt()).isEqualByComparingTo("60");
        assertThat(row.target_annual_profit_increase_amt()).isEqualByComparingTo("150");
        assertThat(row.mm_qty().scale()).isEqualTo(2);
    }
    @Test void exactEffectDoesNotLoseOneWonFromRepeatingIntermediateUnitPrice() {
        var row = calculate(candidate(17, 7, 100), allocation(17, 7, "0.30", 27L, 0), new BigDecimal("5"), today, false);
        assertThat(row.target_annual_profit_increase_amt()).isEqualByComparingTo("45");
        assertThat(row.annual_profit_increase_amt()).isEqualByComparingTo("18");
    }
    @Test void zeroMmMakesTargetAndEffectsUnavailable() {
        var row = calculate(candidate(17, 7, 100), allocation(17, 7, "0.00", 90L, 0), BigDecimal.ZERO, today, false);
        assertThat(row.margin_rt()).isNull();
        assertThat(row.target_price_amt()).isNull();
        assertThat(row.required_raise_rt()).isNull();
        assertThat(row.annual_profit_increase_amt()).isNull();
    }
    @Test void zeroPriceDoesNotFabricateMarginOrRaiseRateOrWeightedRevenue() {
        var row = calculate(candidate(17, 7, 0), allocation(17, 7, "1.00", 90L, 0), new BigDecimal("5"), today, false);
        assertThat(row.margin_rt()).isNull();
        assertThat(row.required_raise_rt()).isNull();
        assertThat(row.annual_profit_increase_amt()).isNull();
        assertThat(percentage(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
    }
    @Test void missingCostNeverBecomesKnownZeroCost() {
        var row = calculate(candidate(17, 7, 100), allocation(17, 7, "1.00", null, 0), BigDecimal.TEN, today, false);
        assertThat(row.monthly_cost_amt()).isNull();
        assertThat(row.margin_rt()).isNull();
        assertThat(row.target_price_amt()).isNull();
    }
    @Test void missingAssignmentDataHasNoInventedMmSalaryOrPrice() {
        var row = calculate(candidate(17, 7, 100), null, BigDecimal.TEN, today, true);
        assertThat(row.mm_qty()).isNull();
        assertThat(row.lbr_cst_amt()).isNull();
        assertThat(row.drct_expns_amt()).isNull();
        assertThat(row.bill_untprc_amt()).isNull();
        assertThat(row.target_price_amt()).isNull();
    }
    @Test void negativeAdjustmentAmountsKeepTheirSignAndNoMoneyIntermediateIsRoundedToCents() {
        var row = calculate(candidate(17, 7, 100), allocation(17, 7, "0.33", -90L, -1), new BigDecimal("5.12345"), today, false);
        assertThat(row.monthly_cost_amt()).isEqualByComparingTo("-91");
        assertThat(row.target_annual_profit_increase_amt()).isEqualByComparingTo("-1761");
        assertThat(row.annual_profit_increase_amt()).isEqualByComparingTo("20");
    }
}
