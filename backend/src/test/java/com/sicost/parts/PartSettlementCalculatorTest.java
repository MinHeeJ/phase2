package com.sicost.parts;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.parts.PartSettlementModels.*;
import com.sicost.common.costs.CostAllocationModels.Allocation;

class PartSettlementCalculatorTest {
    final YearMonth month = YearMonth.of(2026, 10);
    final Standard standard = new Standard(4, "기준", new BigDecimal("40"), 0);
    Salary salary(long id, String type, long paid, long supply, long retirement, LocalDate start) {
        return new Salary(id, "인력" + id, type, paid, supply, retirement, start);
    }
    Allocation allocation(long dev, long assignment, long sales) {
        return new Allocation(dev, 9L, assignment, "PROJECT", new BigDecimal("0.50"), BigDecimal.ONE,
            new BigDecimal("0.50"), 0, 0, sales, sales, false, false, false);
    }
    @Test void negativePersonOffsetsPositiveAndCardDeductedOnce() {
        var a = PartSettlementCalculator.person(month, salary(1, "FT", 100, 100, 0, LocalDate.of(2026, 9, 1)),
            standard, List.of(allocation(1, 1, 200)), new ArrayList<>());
        var b = PartSettlementCalculator.person(month, salary(2, "FL", 100, 100, 0, LocalDate.of(2026, 9, 1)),
            standard, List.of(allocation(2, 2, 80)), new ArrayList<>());
        assertThat(a.prft_amt()).isEqualTo(40); assertThat(b.prft_amt()).isEqualTo(-8);
        assertThat(PartSettlementCalculator.partProfit(List.of(a, b), 10)).isEqualTo(22);
        assertThat(PartSettlementCalculator.partProfit(List.of(a), 100)).isEqualTo(-60);
    }
    @Test void firstMonthInclusiveCalendarDaysOnceAcrossConcurrentAssignments() {
        var row = PartSettlementCalculator.person(month, salary(1, "FT", 3100, 9000, 10, LocalDate.of(2026, 10, 17)),
            standard, List.of(allocation(1, 1, 1000), allocation(1, 2, 1000)), new ArrayList<>());
        assertThat(row.slry_amt()).isEqualTo(1500); assertThat(row.mm_qty()).isEqualByComparingTo("1.00");
        assertThat(row.rtrpay_amt()).isEqualTo(10); assertThat(row.tot_exp_amt()).isEqualTo(1510);
    }
    @Test void monthEndAndLeapYearDayAreInclusive() {
        var row = PartSettlementCalculator.person(YearMonth.of(2024, 2),
            salary(1, "FL", 2900, 2900, 0, LocalDate.of(2024, 2, 29)), standard,
            List.of(allocation(1, 1, 0)), new ArrayList<>());
        assertThat(row.slry_amt()).isEqualTo(100);
    }
    @Test void outsourcedUsesSupplyNotVatInclusiveAndNoFeeOrRetirement() {
        var row = PartSettlementCalculator.person(month, salary(1, "OS", 1100, 1000, 50, LocalDate.of(2026, 9, 1)),
            new Standard(4, "기준", new BigDecimal("40"), 300), List.of(allocation(1, 1, 1500)), new ArrayList<>());
        assertThat(row.slry_amt()).isEqualTo(1000); assertThat(row.bsn_fee_amt()).isZero(); assertThat(row.rtrpay_amt()).isZero();
    }
    @Test void contractorRetirementExcludedFreelancerFeeIncluded() {
        var s = new Standard(4, "기준", new BigDecimal("40"), 30);
        for (String type : List.of("CT", "FL")) {
            var row = PartSettlementCalculator.person(month, salary(1, type, 100, 100, 50, LocalDate.of(2026, 9, 1)),
                s, List.of(allocation(1, 1, 150)), new ArrayList<>());
            assertThat(row.rtrpay_amt()).isZero(); assertThat(row.bsn_fee_amt()).isEqualTo(30);
        }
    }
    @Test void negativeFractionUsesMathematicalFloorNotTruncation() {
        var row = PartSettlementCalculator.person(month, salary(1, "FL", 101, 101, 0, LocalDate.of(2026, 9, 1)),
            standard, List.of(allocation(1, 1, 100)), new ArrayList<>());
        assertThat(row.prft_amt()).isEqualTo(-1);
    }
    @Test void missingStartDoesNotInventProration() {
        var warnings = new ArrayList<String>();
        var row = PartSettlementCalculator.person(month, salary(1, "FT", 100, 100, 0, null), standard,
            List.of(allocation(1, 1, 100)), warnings);
        assertThat(row.slry_amt()).isNull(); assertThat(row.prft_amt()).isNull(); assertThat(warnings).isNotEmpty();
        assertThat(PartSettlementCalculator.partProfit(List.of(row), 0)).isNull();
    }
    @Test void missingStandardPreventsInventedProfit() {
        var row = PartSettlementCalculator.person(month, salary(1, "FT", 100, 100, 0, LocalDate.of(2026, 9, 1)),
            null, List.of(allocation(1, 1, 100)), new ArrayList<>());
        assertThat(row.prft_amt()).isNull();
    }
}
