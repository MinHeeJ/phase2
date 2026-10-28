package com.sicost.common;

import static com.sicost.common.validation.BillingAmounts.*;
import static org.assertj.core.api.Assertions.*;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BillingAmountsTest {
    final LocalDate date = LocalDate.of(2026, 10, 28);

    void error(Runnable action, int status, String code, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.field()).isEqualTo(field);
        });
    }

    @Test void revenueSupplyAndVatIncludedReceivableRemainSeparate() {
        var bill = new Amounts(100, 10);
        assertThat(bill.supply()).isEqualTo(100);
        assertThat(bill.total()).isEqualTo(110);
        assertThat(receivable("CONFIRM", bill.total(), 30)).isEqualTo(80);
        assertThat(bill.supply()).isEqualTo(100);
    }

    @ParameterizedTest @ValueSource(strings = {"PLAN", "DRAFT", "CANCEL"})
    void nonconfirmedAmountsNeverBecomeReceivable(String state) {
        assertThat(receivable(state, 110, 0)).isZero();
    }

    @Test void confirmedAdjustmentsAreAddedOnceBeforeSubtractingReceipts() {
        long adjusted = adjustedTotal(110, -22);
        assertThat(adjusted).isEqualTo(88);
        assertThat(receivable("CONFIRM", adjusted, 30)).isEqualTo(58);
    }

    @Test void receiptsDoNotProduceNegativeBalances() {
        assertThat(receivable("CONFIRM", 110, 120)).isZero();
    }

    @Test void splitReceiptsExhaustVatIncludedBalance() {
        requireReceipt(110, 0, 30);
        assertThat(receivable("CONFIRM", 110, 30)).isEqualTo(80);
        requireReceipt(110, 30, 50);
        assertThat(receivable("CONFIRM", 110, 80)).isEqualTo(30);
        requireReceipt(110, 80, 30);
        assertThat(receivable("CONFIRM", 110, 110)).isZero();
    }

    @Test void receiptEditExcludesTheReplacedRowFromCumulativeLimit() {
        requireReceipt(110, 50, 60);
        error(() -> requireReceipt(110, 50, 61), 409, "OVER_RECEIPT", "rcpt_amt");
    }

    @Test void lateReceiptAfterFullPaymentIsOverReceipt() {
        error(() -> requireReceipt(110, 110, 1), 409, "OVER_RECEIPT", "rcpt_amt");
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void nonpositiveReceiptIsInputError(long amount) {
        error(() -> requireReceipt(110, 0, amount), 400, "VALIDATION_ERROR", "rcpt_amt");
    }

    @Test void cumulativeReceiptComparisonNeverOverflowsBigint() {
        error(() -> requireReceipt(Long.MAX_VALUE, Long.MAX_VALUE, 1),
            409, "OVER_RECEIPT", "rcpt_amt");
        requireReceipt(Long.MAX_VALUE, 0, Long.MAX_VALUE);
    }

    @Test void adjustmentBelowMoneyAlreadyReceivedRequiresRefund() {
        error(() -> requireRefundSafe(adjustedTotal(110, -22), 100),
            409, "REFUND_REQUIRED", "sply_amt");
    }

    @Test void adjustmentMayEqualAlreadyReceivedAmount() { requireRefundSafe(100, 100); }

    @Test void vatSuggestionUsesEffectiveRateAndWonFloorWithoutFloatingPoint() {
        assertThat(suggestedVat(19, null)).isEqualTo(1);
        assertThat(suggestedVat(199, new BigDecimal("7.5"))).isEqualTo(14);
        assertThat(suggestedVat(Long.MAX_VALUE, BigDecimal.TEN)).isEqualTo(922337203685477580L);
        assertThat(suggestedVat(199, BigDecimal.ZERO)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"-0.01", "100.01"})
    void invalidEffectiveTaxRateIsNotUsed(String rate) {
        error(() -> suggestedVat(100, new BigDecimal(rate)), 400, "VALIDATION_ERROR", "vat_amt");
    }

    @Test void manualVatOverrideIsPreservedOnConfirmation() {
        var bill = new Amounts(100, 7);
        requireConfirmation(bill, false, date);
        assertThat(bill.vat()).isEqualTo(7);
        assertThat(bill.total()).isEqualTo(107);
    }

    @Test void manualSupplyDoesNotNeedToEqualAnySourceDetailSum() {
        var bill = new Amounts(99, 9);
        requireConfirmation(bill, false, date);
        assertThat(bill.supply()).isEqualTo(99);
    }

    @Test void confirmationRequiresBillDate() {
        error(() -> requireConfirmation(new Amounts(100, 10), false, null),
            400, "VALIDATION_ERROR", "bill_dt");
    }

    @ParameterizedTest @ValueSource(longs = {0, -1})
    void normalConfirmationRequiresPositiveSupply(long amount) {
        error(() -> requireConfirmation(new Amounts(amount, 0), false, date),
            400, "VALIDATION_ERROR", "sply_amt");
    }

    @Test void normalConfirmationRejectsNegativeVat() {
        error(() -> requireConfirmation(new Amounts(100, -1), false, date),
            400, "VALIDATION_ERROR", "vat_amt");
    }

    @ParameterizedTest @ValueSource(longs = {0, 1})
    void amendmentConfirmationRequiresNegativeSupply(long amount) {
        error(() -> requireConfirmation(new Amounts(amount, 0), true, date),
            400, "VALIDATION_ERROR", "sply_amt");
    }

    @Test void amendmentConfirmationRejectsPositiveVat() {
        error(() -> requireConfirmation(new Amounts(-100, 1), true, date),
            400, "VALIDATION_ERROR", "vat_amt");
    }

    @Test void amendmentMayHaveZeroOrNegativeVat() {
        requireConfirmation(new Amounts(-100, 0), true, date);
        requireConfirmation(new Amounts(-100, -10), true, date);
    }

    @Test void generatedTotalCannotOverflowStorageRange() {
        error(() -> new Amounts(Long.MAX_VALUE, 1).total(), 400, "VALIDATION_ERROR", "vat_amt");
    }

    @ParameterizedTest @ValueSource(strings = {"1.1", "9223372036854775808", "-9223372036854775809"})
    void fractionalAndOverflowWonAmountsAreRejected(String value) {
        error(() -> exact(new BigDecimal(value), "sply_amt"), 400, "VALIDATION_ERROR", "sply_amt");
    }
}
