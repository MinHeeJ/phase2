package com.sicost.common;

import static org.assertj.core.api.Assertions.*;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ProjectNumbers;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectCommonValuesTest {
    @ParameterizedTest
    @CsvSource({"0,P-2601", "98,P-2699", "99,P-26100", "998,P-26999"})
    void numberUsesContractYearAndNumericMaximum(int maximum, String expected) {
        assertThat(ProjectNumbers.allocated(LocalDate.of(2026, 1, 1), maximum)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 999, Integer.MAX_VALUE})
    void numberNeverExceedsApprovedWidth(int maximum) {
        invalid(() -> ProjectNumbers.allocated(LocalDate.of(2026, 1, 1), maximum), "prj_no");
    }

    @Test void numberNeedsContractStartRatherThanSystemYear() {
        invalid(() -> ProjectNumbers.allocated(null, 0), "cntrct_bgn_dt");
        assertThat(ProjectNumbers.allocated(LocalDate.of(2025, 12, 31), 0)).isEqualTo("P-2501");
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void blankUploadNumberRequestsAllocation(String number) {
        assertThat(ProjectNumbers.supplied(number)).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"P-2601", "P-26100", "P-0001"})
    void uploadNumberPreservesLiteralValue(String number) {
        assertThat(ProjectNumbers.supplied(number)).isEqualTo(number);
    }

    @ParameterizedTest @ValueSource(strings = {"p-2601", " P-2601", "P-2601 ", "P-123", "P-123456", "P-２６０１"})
    void malformedUploadNumberIsNotRepaired(String number) {
        invalid(() -> ProjectNumbers.supplied(number), "prj_no");
    }

    @Test void duplicateHasStableConflict() {
        var error = ProjectNumbers.duplicate();
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("DUPLICATE_PRJ_NO");
        assertThat(error.field()).isEqualTo("prj_no");
    }

    @ParameterizedTest @CsvSource({"TK,,STAGE", "MO,,MONTH", "DP,,MONTH", "MO,DAY,DAY", "DP,HOUR,HOUR", "TK,STAGE,STAGE"})
    void settlementDefaultsAndChoices(String type, String input, String expected) {
        assertThat(ProjectValues.settlement(type, input)).isEqualTo(expected);
    }
    @Test void internalSettlementIsAlwaysNull() {
        assertThat(ProjectValues.settlement("IN", "MONTH")).isNull();
    }
    @Test void turnkeyCannotUseMonthlySettlement() {
        invalid(() -> ProjectValues.settlement("TK", "MONTH"), "stlm_mthd_cd");
    }
    @Test void monthlyCannotUseStageSettlement() {
        invalid(() -> ProjectValues.settlement("MO", "STAGE"), "stlm_mthd_cd");
    }
    @Test void unknownTypeIsFieldError() {
        invalid(() -> ProjectValues.settlement("OTHER", null), "cntrct_ty_cd");
    }
    @Test void budgetRateIsSuppliedFromEffectiveDatabaseValue() {
        var margin = ProjectValues.defaultMargin(new BigDecimal("80"));
        assertThat(margin).isEqualByComparingTo("20");
        assertThat(ProjectValues.targetCost(500000000L, margin)).isEqualTo(400000000L);
        assertThat(ProjectValues.targetCost(123456789L, margin)).isEqualTo(98765431L);
        assertThat(ProjectValues.defaultMargin(new BigDecimal("75"))).isEqualByComparingTo("25");
    }
    @Test void moneyCalculationsDoNotLoseBigintPrecision() {
        assertThat(ProjectValues.targetCost(Long.MAX_VALUE, BigDecimal.ZERO)).isEqualTo(Long.MAX_VALUE);
        assertThat(ProjectValues.vat(19)).isEqualTo(1);
        assertThat(ProjectValues.vat(Long.MAX_VALUE)).isEqualTo(922337203685477580L);
    }
    @ParameterizedTest @ValueSource(strings = {"-0.01", "100.01", "20.001"})
    void invalidMarginDoesNotRound(String margin) {
        invalid(() -> ProjectValues.targetCost(1, new BigDecimal(margin)), "trgt_mrgn_rt");
    }
    @Test void typedMoneyMustBeExactBigint() {
        assertThat(ProjectValues.money(new BigDecimal("9223372036854775807"), 1, "expns_amt"))
            .isEqualTo(Long.MAX_VALUE);
        assertThat(ProjectValues.money(new BigDecimal("300000"), 1, "expns_amt")).isEqualTo(300000);
    }
    @ParameterizedTest @ValueSource(strings = {"9223372036854775808", "1.1", "0", "-1"})
    void typedMoneyRejectsOverflowFractionAndNonpositiveExpense(String amount) {
        invalid(() -> ProjectValues.money(new BigDecimal(amount), 1, "expns_amt"), "expns_amt");
    }
    @Test void missingMoneyIsFieldError() {
        invalid(() -> ProjectValues.money(null, 1, "expns_amt"), "expns_amt");
    }
    @Test void signedMmIsPreservedAndTrailingZerosAreHarmless() {
        assertThat(ProjectValues.decimal(new BigDecimal("-12.34"), 8, "incdec_mm_qty"))
            .isEqualByComparingTo("-12.34");
        assertThat(ProjectValues.decimal(new BigDecimal("99.990"), 5, "mm_qty")).isEqualTo(new BigDecimal("99.99"));
    }
    @ParameterizedTest @ValueSource(strings = {"1.001", "1000.00", "-1000.00"})
    void decimalRejectsScaleAndPhysicalOverflow(String amount) {
        invalid(() -> ProjectValues.decimal(new BigDecimal(amount), 5, "mm_qty"), "mm_qty");
    }
    @Test void defaultDateUsesInjectedSeoulClockAtYearBoundary() {
        var clock = Clock.fixed(Instant.parse("2025-12-31T15:00:00Z"), ZoneOffset.UTC);
        assertThat(ProjectValues.today(clock)).isEqualTo(LocalDate.of(2026, 1, 1));
    }
    @Test void singleDayPeriodIncludesBothEndpoints() {
        var day = LocalDate.of(2026, 1, 1);
        var period = ProjectValues.period(day, day, day, null);
        assertThat(period.days()).isEqualTo(1);
        assertThat(period.intersects(2026)).isTrue();
        assertThat(period.intersects(2025)).isFalse();
    }
    @Test void periodUsesPerformanceAndCurrentOverridesOrContractFallbacks() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 12, 31);
        assertThat(ProjectValues.period(start, end, null, null).intersects(2026)).isFalse();
        var period = ProjectValues.period(start, end, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 3, 31));
        assertThat(period.intersects(2026)).isTrue();
        assertThat(period.contains(YearMonth.of(2027, 3))).isTrue();
        assertThat(period.contains(YearMonth.of(2025, 12))).isFalse();
    }
    @Test void reversedContractPeriodIsInvalid() {
        invalid(() -> ProjectValues.period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), null, null), "cntrct_end_dt");
    }
    @Test void performanceStartCannotPrecedeContract() {
        invalid(() -> ProjectValues.period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 1, 1), null), "prfm_bgn_dt");
    }
    @Test void performanceStartCannotExceedEffectiveEnd() {
        invalid(() -> ProjectValues.period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1), null), "prfm_bgn_dt");
    }
    @ParameterizedTest @ValueSource(strings = {"2026-08", "2026-09"})
    void partMonthCannotBeAtOrBeforeLatestClosed(String month) {
        assertThatThrownBy(() -> ProjectValues.requirePartMonth(month, "2026-09"))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(409);
                assertThat(error.code()).isEqualTo("CLOSED_MONTH");
                assertThat(error.field()).isEqualTo("aply_bgn_ym");
            });
    }
    @Test void openPastAndFuturePartMonthsAreAllowed() {
        ProjectValues.requirePartMonth("2026-10", "2026-09");
        ProjectValues.requirePartMonth("2027-10", "2026-09");
        ProjectValues.requirePartMonth("2025-01", null);
    }
    @Test void malformedPartMonthIsInvalid() {
        invalid(() -> ProjectValues.requirePartMonth("2026-13", null), "aply_bgn_ym");
    }

    private void invalid(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_INPUT");
            assertThat(error.field()).isEqualTo(field);
        });
    }
}
