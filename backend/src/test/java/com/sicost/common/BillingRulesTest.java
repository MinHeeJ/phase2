package com.sicost.common;

import static com.sicost.common.validation.BillingRules.*;
import static org.assertj.core.api.Assertions.*;
import com.sicost.common.api.ApiException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BillingRulesTest {
    final YearMonth october = YearMonth.of(2026, 10);
    final LocalDate billDate = LocalDate.of(2026, 11, 2);

    void error(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(status);
            assertThat(ex.code()).isEqualTo(code);
        });
    }

    @Test void internalProjectsCannotBeBilled() {
        error(() -> requireBillable("IN"), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @ValueSource(strings = {"TK", "MO", "DP"})
    void supportedContractTypesAreBillable(String type) { requireBillable(type); }

    @ParameterizedTest @ValueSource(strings = {"", "UNKNOWN", "tk"})
    void unknownContractTypesAreNotNormalized(String type) {
        error(() -> requireBillable(type), 400, "VALIDATION_ERROR");
    }

    @Test void missingTypeIsValidationNotServerError() {
        error(() -> requireBillable(null), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @CsvSource({"TK,2026-11", "MO,2026-10", "DP,2026-10"})
    void revenueMonthDependsOnContractNeverReceiptDate(String type, String expected) {
        var period = new Period(type, october, billDate, LocalDate.of(2026, 9, 1));
        assertThat(period.revenueMonth()).hasToString(expected);
        requireReceiptAmount(1100, 0, 300);
        requireReceiptAmount(1100, 300, 800);
        assertThat(period.revenueMonth()).hasToString(expected);
    }

    @Test void undatedTkDraftUsesStageForCloseButNeverForConfirmedRevenue() {
        var period = new Period("TK", null, null, october.atDay(1));
        assertThat(period.billingMonth()).isEqualTo(october);
        error(period::revenueMonth, 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @ValueSource(strings = {"MO", "DP"})
    void monthlyContractsRequireAttributionEvenWithBillDate(String type) {
        error(() -> new Period(type, null, billDate, billDate).billingMonth(), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @ValueSource(strings = {"TK", "MO", "DP"})
    void closedBillingMonthRejectsWritesForEveryContract(String type) {
        var period = new Period(type, october, october.atDay(1), null);
        error(() -> requireOpen(period.billingMonth(), "CLOSED", "month"), 409, "CLOSED_MONTH");
    }

    @Test void draftDateMoveChecksOldAndTargetMonths() {
        var oldPeriod = new Period("TK", null, october.atDay(1), null);
        var newPeriod = new Period("TK", null, billDate, null);
        error(() -> requireOpenChange(oldPeriod, newPeriod,
            Map.of(october, "CLOSED")), 409, "CLOSED_MONTH");
        error(() -> requireOpenChange(oldPeriod, newPeriod,
            Map.of(YearMonth.from(billDate), "CLOSED")), 409, "CLOSED_MONTH");
        requireOpenChange(oldPeriod, newPeriod, Map.of());
    }

    @Test void pastYearIsNotAnApiLockAndMissingCloseRowIsOpen() {
        requireOpen(YearMonth.of(2020, 1), null, "month");
        requireOpen(YearMonth.of(2020, 1), "OPEN", "month");
    }

    @Test void receiptCloseChecksOriginalAndTargetIndependentOfBillingMonth() {
        error(() -> requireReceiptMonths(october.atDay(1), billDate,
            Map.of(october, "CLOSED")), 409, "CLOSED_MONTH");
        error(() -> requireReceiptMonths(october.atDay(1), billDate,
            Map.of(YearMonth.from(billDate), "CLOSED")), 409, "CLOSED_MONTH");
        requireReceiptMonths(null, billDate, Map.of(october, "CLOSED"));
        error(() -> requireReceiptMonths(october.atDay(1), null,
            Map.of(october, "CLOSED")), 409, "CLOSED_MONTH");
    }

    @ParameterizedTest @EnumSource(Status.class)
    void onlyConfirmContributesSupplyAndVatInclusiveReceivable(Status status) {
        assertThat(confirmedSupply(status, 1000)).isEqualTo(status == Status.CONFIRM ? 1000 : 0);
        assertThat(receivable(status, 1100, 300)).isEqualTo(status == Status.CONFIRM ? 800 : 0);
        assertThat(plannedSupply(status, 1000)).isEqualTo(status == Status.PLAN ? 1000 : 0);
        assertThat(draftSupply(status, 1000)).isEqualTo(status == Status.DRAFT ? 1000 : 0);
    }

    @Test void confirmedNegativeAmendmentIsRevenueNotCashAndBalanceIsFlooredAtZero() {
        assertThat(confirmedSupply(Status.CONFIRM, -100)).isEqualTo(-100);
        assertThat(receivable(Status.CONFIRM, 990, 1000)).isZero();
    }

    @Test void validSavedDraftCanConfirmWithoutInvoiceOrDetailMatchingParameters() {
        requireConfirmation(Status.DRAFT, billDate, 1000, 12, false);
        requireConfirmation(Status.DRAFT, billDate, -100, 0, true);
    }

    @Test void missingBillDatePreventsConfirmation() {
        error(() -> requireConfirmation(Status.DRAFT, null, 1000, 100, false), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @ValueSource(longs = {0, -1})
    void normalConfirmationRequiresPositiveSupply(long amount) {
        error(() -> requireConfirmation(Status.DRAFT, billDate, amount, 0, false), 400, "VALIDATION_ERROR");
    }

    @Test void normalVatMustBeNonnegative() {
        error(() -> requireConfirmation(Status.DRAFT, billDate, 100, -1, false), 400, "VALIDATION_ERROR");
    }

    @Test void amendmentSupplyMustBeNegative() {
        error(() -> requireConfirmation(Status.DRAFT, billDate, 0, 0, true), 400, "VALIDATION_ERROR");
    }

    @Test void amendmentVatMustBeNonpositive() {
        error(() -> requireConfirmation(Status.DRAFT, billDate, -100, 1, true), 400, "VALIDATION_ERROR");
    }

    @Test void generatedTotalMustNotOverflow() {
        error(() -> requireConfirmation(Status.DRAFT, billDate, Long.MAX_VALUE, 1, false),
            400, "VALIDATION_ERROR");
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"PLAN", "CONFIRM", "CANCEL"})
    void lateConfirmationAndDraftEditAreStale(Status status) {
        error(() -> requireDraft(status), 409, "STALE_BILL");
        error(() -> requireConfirmation(status, billDate, 100, 0, false), 409, "STALE_BILL");
    }

    @Test void lostConditionalUpdateMustNotBeReportedSuccessful() {
        requireChanged(1);
        error(() -> requireChanged(0), 409, "STALE_BILL");
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"PLAN", "DRAFT", "CANCEL"})
    void onlyConfirmedRootAcceptsReceipts(Status status) {
        error(() -> requireReceiptTarget(status, false), 409, "STALE_BILL");
    }

    @Test void amendmentsNeverAcceptReceipts() {
        error(() -> requireReceiptTarget(Status.CONFIRM, true), 409, "STALE_BILL");
        requireReceiptTarget(Status.CONFIRM, false);
    }

    @Test void latestLockedBalanceAllowsExactRemainingReceipt() {
        requireReceiptAmount(1100, 300, 800);
    }

    @Test void lateReceiptAgainstLockedBalanceIsOverReceipt() {
        error(() -> requireReceiptAmount(1100, 300, 801), 409, "OVER_RECEIPT");
    }

    @Test void receiptAccumulationOverflowIsOverReceiptNotWrappedSuccess() {
        error(() -> requireReceiptAmount(Long.MAX_VALUE, Long.MAX_VALUE, 1), 409, "OVER_RECEIPT");
    }

    @ParameterizedTest @ValueSource(longs = {0, -1})
    void receiptAmountMustBePositive(long amount) {
        error(() -> requireReceiptAmount(1100, 0, amount), 400, "VALIDATION_ERROR");
    }

    @Test void amendmentCannotReduceTotalBelowAlreadyReceivedCash() {
        error(() -> requireAdjustedTotal(1100, -220, 900), 409, "REFUND_REQUIRED");
        requireAdjustedTotal(1100, -220, 880);
    }

    @Test void amendmentCannotExceedRemainingCurrentBillTotal() {
        error(() -> requireAdjustedTotal(1100, -1101, 0), 400, "VALIDATION_ERROR");
    }
}
