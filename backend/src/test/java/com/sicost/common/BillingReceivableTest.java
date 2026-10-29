package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BillingReceivableTest {
    final BillingRevenueQuery query = mock(BillingRevenueQuery.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-31T15:00:00Z"), ZoneOffset.UTC);
    final BillingReceivableService service = new BillingReceivableService(query, clock);
    Balance balance(String status, String amendment, long adjustments, long received) {
        return new Balance(1, status, amendment, 1100, adjustments, received);
    }
    void conflict(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo(code);
        });
    }
    @Test void receiptAndRevenueStaySeparateIncludingConfirmedNegativeAmendments() {
        var row = balance("CONFIRM", "N", -220, 330);
        when(query.balance(1)).thenReturn(row);
        assertThat(service.balance(1).adjustedTotal()).isEqualTo(880);
        assertThat(service.balance(1).receivable()).isEqualTo(550);
        verify(query, times(2)).balance(1);
    }
    @ParameterizedTest @ValueSource(strings = {"PLAN", "DRAFT", "CANCEL"})
    void nonConfirmedStatesNeverAccrueReceivables(String state) {
        assertThat(balance(state, "N", 0, 0).receivable()).isZero();
        conflict(() -> service.requireReceipt(balance(state, "N", 0, 0), 100, 0), "STALE_BILL");
    }
    @Test void amendmentIsNotAnIndependentReceivableOrReceiptTarget() {
        assertThat(balance("CONFIRM", "Y", 0, 0).receivable()).isZero();
        conflict(() -> service.requireReceipt(balance("CONFIRM", "Y", 0, 0), 100, 0), "STALE_BILL");
    }
    @Test void splitReceiptsAllowExactlyRemainingBalance() {
        service.requireReceipt(balance("CONFIRM", "N", -220, 330), 550, 0);
    }
    @Test void receiptEditExcludesOnlyTheReceiptBeingReplaced() {
        service.requireReceipt(balance("CONFIRM", "N", 0, 600), 700, 200);
    }
    @Test void lateReceiptBeyondLatestBalanceConflicts() {
        conflict(() -> service.requireReceipt(balance("CONFIRM", "N", 0, 1100), 1, 0), "OVER_RECEIPT");
    }
    @Test void adjustmentBelowAlreadyReceivedRequiresRefund() {
        conflict(() -> service.requireAdjustment(balance("CONFIRM", "N", -100, 800), -201), "REFUND_REQUIRED");
    }
    @Test void adjustmentEqualToReceivedRemainsAllowed() {
        service.requireAdjustment(balance("CONFIRM", "N", -100, 800), -200);
    }
    @ParameterizedTest @ValueSource(longs = {0, -1})
    void receiptAmountMustBePositive(long amount) {
        assertThatThrownBy(() -> service.requireReceipt(balance("CONFIRM", "N", 0, 0), amount, 0))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(400); assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
                assertThat(e.field()).isEqualTo("rcpt_amt");
            });
    }
    @ParameterizedTest @CsvSource({"-1,0,7", "0,0,7", "6,0,7", "7,1,30", "29,1,30", "30,2,60", "59,2,60"})
    void reminderBoundariesAndNextDateUseSeoulToday(int days, int stage, int nextDays) {
        LocalDate due = LocalDate.of(2026, 11, 1).minusDays(days);
        assertThat(service.reminderStage(due, 100)).isEqualTo(stage);
        assertThat(service.nextNotificationDate(due, 100)).isEqualTo(due.plusDays(nextDays));
    }
    @ParameterizedTest @ValueSource(ints = {60, 90, 120})
    void finalReminderHasNoNextDate(int days) {
        var due = LocalDate.of(2026, 11, 1).minusDays(days);
        assertThat(service.reminderStage(due, 1)).isEqualTo(3);
        assertThat(service.nextNotificationDate(due, 1)).isNull();
    }
    @Test void noDueDateOrNoBalanceHasNoReminder() {
        assertThat(service.nextNotificationDate(null, 100)).isNull();
        assertThat(service.nextNotificationDate(LocalDate.of(2026, 10, 1), 0)).isNull();
    }
    @Test void absentBalanceIsNotFound() {
        assertThatThrownBy(() -> service.balance(99)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.status()).isEqualTo(404));
    }
}
