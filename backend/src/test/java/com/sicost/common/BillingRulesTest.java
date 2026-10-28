package com.sicost.common;

import static com.sicost.common.validation.BillingRules.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BillingRulesTest {
    void error(Runnable action, int status, String code, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.field()).isEqualTo(field);
        });
    }

    @Test void internalProjectsCannotBeBilled() {
        error(() -> contractType("IN"), 400, "VALIDATION_ERROR", "contract_type");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"OTHER", "tk", " TK"})
    void unknownContractTypesAreNotNormalized(String type) {
        error(() -> contractType(type), 400, "VALIDATION_ERROR", "contract_type");
    }
    @ParameterizedTest @ValueSource(strings = {"TK", "MO", "DP"})
    void supportedProjectTypesArePreserved(String type) { assertThat(contractType(type)).isEqualTo(type); }

    @Test void turnkeyRevenueUsesInvoiceDateNotAttributionMonth() {
        assertThat(revenueMonth("TK", "2026-09", LocalDate.of(2026, 10, 1)))
            .isEqualTo(YearMonth.of(2026, 10));
    }
    @ParameterizedTest @ValueSource(strings = {"MO", "DP"})
    void monthlyRevenueDoesNotFollowInvoiceDate(String type) {
        assertThat(revenueMonth(type, "2026-09", LocalDate.of(2026, 10, 1)))
            .isEqualTo(YearMonth.of(2026, 9));
        assertThat(revenueMonth(type, "2026-09", null)).isEqualTo(YearMonth.of(2026, 9));
    }
    @Test void turnkeyRevenueNeedsInvoiceDate() {
        error(() -> revenueMonth("TK", null, null), 400, "VALIDATION_ERROR", "bill_dt");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"2026-13", "2026-1", "0000-01", "2026-10-01"})
    void strictMonthsRetainBillingErrorCode(String value) {
        error(() -> month(value, "month"), 400, "VALIDATION_ERROR", "month");
    }
    @ParameterizedTest @ValueSource(strings = {"2026-02-29", "2026-1-01", "2026-10", "0000-01-01"})
    void strictDatesRetainBillingErrorCode(String value) {
        error(() -> date(value, "bill_dt"), 400, "VALIDATION_ERROR", "bill_dt");
    }
    @Test void isoDateAndMonthAcceptLeapDay() {
        assertThat(date("2024-02-29", "bill_dt")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(month("2024-02", "month")).isEqualTo(YearMonth.of(2024, 2));
    }

    @ParameterizedTest @CsvSource({"PLAN,0,100,0", "DRAFT,0,0,100", "CONFIRM,100,0,0", "CANCEL,0,0,0"})
    void fourStatesKeepActualPlannedAndDraftSupplySeparate(String state, long actual, long planned, long draft) {
        assertThat(status(state)).isEqualTo(state);
        assertThat(confirmedSupply(state, 100)).isEqualTo(actual);
        assertThat(plannedSupply(state, 100)).isEqualTo(planned);
        assertThat(draftSupply(state, 100)).isEqualTo(draft);
    }
    @Test void confirmedNegativeAmendmentsReduceRevenue() {
        assertThat(confirmedSupply("CONFIRM", -100)).isEqualTo(-100);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"CONFIRMED", "confirm"})
    void unknownStatesAreNotInvented(String state) {
        error(() -> status(state), 400, "VALIDATION_ERROR", "bill_stts_cd");
    }
    @Test void supplyAndVatIncludedReceivableAreDifferentAndReceiptsDoNotChangeRevenueMonth() {
        var month = revenueMonth("MO", "2026-09", LocalDate.of(2026, 10, 1));
        assertThat(confirmedSupply("CONFIRM", 100)).isEqualTo(100);
        assertThat(receivable("CONFIRM", total(100, 10), 0, 0)).isEqualTo(110);
        assertThat(receivable("CONFIRM", total(100, 10), 0, 60)).isEqualTo(50);
        assertThat(revenueMonth("MO", "2026-09", LocalDate.of(2026, 10, 1))).isEqualTo(month);
    }
    @ParameterizedTest @ValueSource(strings = {"PLAN", "DRAFT", "CANCEL"})
    void nonconfirmedRowsNeverHaveReceivables(String state) {
        assertThat(receivable(state, 110, 0, 0)).isZero();
    }
    @Test void confirmedAmendmentTotalReducesVatIncludedBalanceWithoutJoinMultiplication() {
        assertThat(receivable("CONFIRM", 1100, -220, 300)).isEqualTo(580);
    }
    @Test void amendmentBelowReceivedRequiresRefund() {
        error(() -> receivable("CONFIRM", 1100, -220, 900), 409, "REFUND_REQUIRED", "rcpt_amt");
    }
    @Test void excessReceiptHasSpecificConflict() {
        error(() -> receipt(51, 50), 409, "OVER_RECEIPT", "rcpt_amt");
    }
    @Test void legitimateSplitReceiptsCanConsumeRemainingBalance() {
        receipt(60, 110);
        receipt(50, receivable("CONFIRM", 110, 0, 60));
        assertThat(receivable("CONFIRM", 110, 0, 110)).isZero();
    }
    @ParameterizedTest @ValueSource(longs = {0, -1})
    void receiptMustBePositive(long amount) {
        error(() -> receipt(amount, 100), 400, "VALIDATION_ERROR", "rcpt_amt");
    }
    @ParameterizedTest @ValueSource(strings = {"1.5", "1.0", "9223372036854775808", "\"2\"", "null", "true"})
    void wireMoneyRejectsFractionsOverflowAndCoercion(String value) throws Exception {
        var node = new ObjectMapper().readTree(value);
        error(() -> won(node, "sply_amt"), 400, "VALIDATION_ERROR", "sply_amt");
    }
    @ParameterizedTest @ValueSource(strings = {"9223372036854775807", "-9223372036854775808", "0", "-1"})
    void signedLongMoneyIsExact(String value) throws Exception {
        assertThat(won(new ObjectMapper().readTree(value), "sply_amt")).isEqualTo(Long.parseLong(value));
    }
    @Test void totalCannotOverflowBigint() {
        error(() -> total(Long.MAX_VALUE, 1), 400, "VALIDATION_ERROR", "tot_amt");
        error(() -> total(Long.MIN_VALUE, -1), 400, "VALIDATION_ERROR", "tot_amt");
    }
    @Test void normalDraftCanBeZeroButConfirmationRequiresPositiveSupply() {
        amounts(0, 0, false, false);
        error(() -> amounts(0, 0, false, true), 400, "VALIDATION_ERROR", "sply_amt");
        amounts(1, 0, false, true);
    }
    @Test void normalSupplyCannotBeNegative() {
        error(() -> amounts(-1, 0, false, false), 400, "VALIDATION_ERROR", "sply_amt");
    }
    @Test void normalVatCannotBeNegative() {
        error(() -> amounts(1, -1, false, true), 400, "VALIDATION_ERROR", "vat_amt");
    }
    @Test void amendmentRequiresNegativeSupply() {
        error(() -> amounts(0, 0, true, false), 400, "VALIDATION_ERROR", "sply_amt");
    }
    @Test void amendmentVatCannotBePositive() {
        error(() -> amounts(-1, 1, true, true), 400, "VALIDATION_ERROR", "vat_amt");
        amounts(-100, -10, true, true);
        amounts(-100, 0, true, true);
    }
    @Test void sourceCalculationFloorsWonWithoutFloatingPoint() {
        assertThat(sourceAmount(new BigDecimal("0.33"), 101)).isEqualTo(33);
        assertThat(sourceAmount(BigDecimal.ONE, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(sourceAmount(BigDecimal.ZERO, 101)).isZero();
    }
    @Test void sourceCalculationOverflowIsValidationNotSqlFailure() {
        error(() -> sourceAmount(BigDecimal.TEN, Long.MAX_VALUE), 400, "VALIDATION_ERROR", "bill_amt");
    }
    @Test void vatUsesEffectiveRateAndFallbackOnlyWhenAbsent() {
        assertThat(vat(199, new BigDecimal("5"))).isEqualTo(9);
        assertThat(vat(199, BigDecimal.ZERO)).isZero();
        assertThat(vat(199, null)).isEqualTo(19);
    }
    @ParameterizedTest @ValueSource(strings = {"-1", "100.01", "1.001"})
    void malformedEffectiveRateIsNotSilentlyReplaced(String value) {
        error(() -> vat(100, new BigDecimal(value)), 400, "VALIDATION_ERROR", "VAT_RATE");
    }
    @Test void attendanceMmRoundsHalfUpSeparatelyFromSourceMm() {
        assertThat(attendanceMm(BigDecimal.ONE, new BigDecimal("8"))).isEqualByComparingTo("0.13");
    }
    @Test void attendanceDenominatorMustBePositive() {
        error(() -> attendanceMm(BigDecimal.ONE, BigDecimal.ZERO), 400, "VALIDATION_ERROR", "mth_work_day_qty");
    }
    @ParameterizedTest @ValueSource(strings = {"PLAN", "CONFIRM", "CANCEL"})
    void onlyDraftMayBeConfirmedOrEdited(String state) {
        error(() -> draft(state), 409, "STALE_BILL", "bill_id");
    }
    @Test void conditionalUpdateMustAffectExactlyOneRow() {
        draft("DRAFT"); changed(1);
        error(() -> changed(0), 409, "STALE_BILL", "bill_id");
    }
    @Test void closedMonthRejectsWriteRegardlessOfYear() {
        error(() -> openMonth("2026-10", "CLOSED"), 409, "CLOSED_MONTH", "month");
    }
    @Test void pastYearIsNotAnApiLockAndMissingMonthCloseMeansOpen() {
        openMonth("2025-01", null);
        openMonth("2025-02", "OPEN");
    }
}
