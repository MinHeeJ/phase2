package com.sicost.costsops;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.sicost.common.costs.CostAllocationModels.*;
import static com.sicost.costsops.RenegotiationModels.*;
import static org.assertj.core.api.Assertions.*;

class RenegotiationCalculatorTest {
    static BigDecimal n(String s) { return new BigDecimal(s); }
    Row row(long id, String mm, Long price, long cost) {
        return RenegotiationCalculator.row(new Candidate(id,id,"사람","FT",11,"프로젝트","MO",LocalDate.of(2026,12,31)),
            new Allocation(id,11L,id,"PROJECT",n(mm),n(mm),BigDecimal.ONE,cost,0,null,price,false,false,false),
            n("20"),n("5"),LocalDate.of(2026,10,23),false);
    }
    @Test void exactTargetIsOnlyRoundedForDisplayAndAnnualEffectNotInflated() {
        var row = row(1,"1.00",1000000L,900000);
        assertThat(row.margin_rt()).isEqualByComparingTo("10.0");
        assertThat(row.target_untprc_amt()).isEqualByComparingTo("1130000");
        assertThat(row.required_raise_rt()).isEqualByComparingTo("12.5");
        assertThat(row.annual_increase_amt()).isEqualByComparingTo("600000");
        assertThat(row.target_annual_increase_amt()).isEqualByComparingTo("1500000");
    }
    @Test void zeroMmDoesNotInventRatioOrTarget() {
        var row = row(1,"0.00",100L,90);
        assertThat(row.margin_rt()).isNull();
        assertThat(row.target_untprc_amt()).isNull();
        assertThat(row.required_raise_rt()).isNull();
        assertThat(row.warnings()).isNotEmpty();
    }
    @Test void zeroPriceDoesNotPolluteWeightedMargin() {
        var zero = row(1,"1.00",0L,90);
        assertThat(zero.margin_rt()).isNull();
        assertThat(zero.required_raise_rt()).isNull();
        assertThat(RenegotiationCalculator.summary(List.of(zero,row(2,"1.00",100L,90))).weighted_margin_rt())
            .isEqualByComparingTo("10.0");
    }
    @Test void nullPriceIsMissingEvidenceRatherThanZero() {
        var row = row(1,"1.00",null,90);
        assertThat(row.margin_rt()).isNull();
        assertThat(row.annual_increase_amt()).isNull();
        assertThat(row.warnings()).contains("단가 미등록");
    }
    @Test void weightedMarginUsesRevenueNotAverageOfRatios() {
        var result = RenegotiationCalculator.summary(List.of(row(1,"1.00",100L,90),row(2,"1.00",900L,900)));
        assertThat(result.weighted_margin_rt()).isEqualByComparingTo("1.0");
        assertThat(result.annual_increase_amt()).isEqualByComparingTo("600");
    }
    @Test void strictFilterUsesExactMarginBeforeOneDecimalDisplayRounding() {
        var below = row(1,"1.00",10000L,8501);
        var equal = row(2,"1.00",10000L,8500);
        assertThat(below.margin_rt()).isEqualByComparingTo("15.0");
        assertThat(RenegotiationCalculator.below(below,n("15"))).isTrue();
        assertThat(RenegotiationCalculator.below(equal,n("15"))).isFalse();
    }
    @Test void negativeProfitRemainsNegativeAndTargetIncreaseNeverNegative() {
        assertThat(row(1,"1.00",100L,120).margin_rt()).isEqualByComparingTo("-20");
        assertThat(row(2,"1.00",100L,50).target_annual_increase_amt()).isEqualByComparingTo("0");
    }
    @Test void hundredPercentTargetIsUnavailableNotDivisionFailure() {
        var row = RenegotiationCalculator.row(RenegotiationApiTest.candidate(1),
            new Allocation(7L,11L,1L,"PROJECT",n("1"),n("1"),n("1"),90,0,100L,100L,false,false,false),
            n("100"),n("5"),LocalDate.of(2026,10,23),false);
        assertThat(row.target_untprc_amt()).isNull();
        assertThat(row.target_annual_increase_amt()).isNull();
    }
}
