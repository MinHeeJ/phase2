package com.sicost.common;

import static com.sicost.common.billing.BillingRules.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sicost.common.api.ApiException;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadMapper;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BillingRulesTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"), ZoneOffset.UTC);
    final BillingAmountService amounts = new BillingAmountService(mock(SharedReadMapper.class));

    void error(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test void internalProjectBillingIs400() { error(() -> billable("IN"), 400, "VALIDATION_ERROR"); }
    @ParameterizedTest @ValueSource(strings = {"TK", "MO", "DP"})
    void supportedTypesRemainBillable(String type) { billable(type); }

    @Test void turnkeyRevenueUsesBillDateNotAttributionOrReceiptDate() {
        var month = revenueMonth("TK", "2026-08", LocalDate.of(2026, 10, 1));
        assertThat(month).isEqualTo(YearMonth.of(2026, 10));
        var received = new CashReceipt(1, 1, LocalDate.of(2026, 11, 1), 110);
        assertThat(received.rcpt_dt().getMonthValue()).isEqualTo(11);
        assertThat(revenueMonth("TK", "2026-08", LocalDate.of(2026, 10, 1))).isEqualTo(month);
    }
    @ParameterizedTest @ValueSource(strings = {"MO", "DP"})
    void monthlyRevenueUsesAttributionEvenWithLaterBilling(String type) {
        assertThat(revenueMonth(type, "2026-09", LocalDate.of(2026, 10, 1)))
            .isEqualTo(YearMonth.of(2026, 9));
    }
    @Test void confirmationRequiresDateButDraftUsesStageMonthThenCreationMonth() {
        error(() -> revenueMonth("TK", null, null), 400, "VALIDATION_ERROR");
        var creation = LocalDateTime.of(2026, 11, 1, 0, 1);
        assertThat(effectiveMonth("TK", null, null, LocalDate.of(2026, 9, 30), creation))
            .isEqualTo(YearMonth.of(2026, 9));
        assertThat(effectiveMonth("TK", null, null, null, creation)).isEqualTo(YearMonth.of(2026, 11));
    }
    @ParameterizedTest @ValueSource(strings = {"TK", "MO", "DP"})
    void closedMonthRejectsAllBillingTypes(String type) {
        var month = effectiveMonth(type, "2026-09", LocalDate.of(2026, 9, 30), null, null);
        error(() -> requireOpen(month, "CLOSED", "month"), 409, "CLOSED_MONTH");
    }
    @Test void oldYearUiDoesNotImplyApiMonthClosed() {
        assertThat(pastYear(2025, clock)).isTrue();
        requireOpen(YearMonth.of(2025, 10), "OPEN", "month");
        assertThat(pastYear(2026, clock)).isFalse();
        error(() -> requireOpen(YearMonth.of(2026, 10), "CLOSED", "month"), 409, "CLOSED_MONTH");
    }
    @ParameterizedTest @ValueSource(strings = {"PLAN", "CONFIRM", "CANCEL"})
    void nonDraftCannotBeEditedOrConfirmedAgain(String state) {
        error(() -> requireDraft(state), 409, "STALE_BILL");
    }
    @Test void draftIsWritableAndConditionalUpdateRequiresExactlyOneRow() {
        requireDraft("DRAFT"); requireAffected(1);
        error(() -> requireAffected(0), 409, "STALE_BILL");
    }
    @Test void numberMonthUsesSeoulCreationClockAndDoesNotTruncateSequence() {
        assertThat(numberPrefix(clock)).isEqualTo("B-2611-");
        assertThat(number(numberPrefix(clock), 1)).isEqualTo("B-2611-01");
        assertThat(number(numberPrefix(clock), 100)).isEqualTo("B-2611-100");
        error(() -> number(numberPrefix(clock), Long.MAX_VALUE), 400, "VALIDATION_ERROR");
    }
    @ParameterizedTest @ValueSource(strings = {"1.1", "9223372036854775808", "-9223372036854775809"})
    void wonMustBeFiniteIntegerWithinBigint(String value) {
        error(() -> won(new BigDecimal(value), "sply_amt"), 400, "VALIDATION_ERROR");
    }
    @Test void arithmeticOverflowIsField400NotDatabase500() {
        error(() -> add(Long.MAX_VALUE, 1, "sply_amt"), 400, "VALIDATION_ERROR");
        error(() -> subtract(Long.MIN_VALUE, 1, "sply_amt"), 400, "VALIDATION_ERROR");
        error(() -> amounts.lineAmount(Long.MAX_VALUE, new BigDecimal("2"), false), 400, "VALIDATION_ERROR");
    }
    @ParameterizedTest @CsvSource({"5,2,999.99", "5,1,9999.9", "6,1,99999.9"})
    void exactStorageQuantityBoundsAreAccepted(int precision, int scale, String value) {
        assertThat(quantity(new BigDecimal(value), precision, scale, "quantity")).isEqualByComparingTo(value);
    }
    @ParameterizedTest @CsvSource({"5,2,1000", "5,2,0.001", "5,1,0.01", "6,1,100000", "5,1,-0.1"})
    void enteredQuantitiesAreNeverSilentlyRounded(int precision, int scale, String value) {
        error(() -> quantity(new BigDecimal(value), precision, scale, "quantity"), 400, "VALIDATION_ERROR");
    }
    @Test void supplyUsesFloorAndFreeSupportNeverAccruesOvertime() {
        assertThat(amounts.lineAmount(101, new BigDecimal("0.52"), false)).isEqualTo(52);
        assertThat(amounts.hourlyAmount(101, new BigDecimal("1.5"), 17, false)).isEqualTo(168);
        assertThat(amounts.hourlyAmount(101, new BigDecimal("1.5"), 17, true)).isZero();
        assertThat(amounts.attendanceMm(new BigDecimal("10.5"), new BigDecimal("20")))
            .isEqualByComparingTo("0.53");
    }
    @Test void zeroMonthlyWorkdaysIsInvalid() {
        error(() -> amounts.attendanceMm(BigDecimal.ONE, BigDecimal.ZERO), 400, "VALIDATION_ERROR");
    }
    @Test void vatIsSeparateFromSupplyAndAmendmentSignsAreValidated() {
        assertThat(amounts.suggestedVat(105, "2026-10")).isEqualTo(10);
        assertThat(amounts.total(105, 10, false)).isEqualTo(115);
        assertThat(amounts.total(-100, -10, true)).isEqualTo(-110);
        error(() -> amounts.total(100, -10, false), 400, "VALIDATION_ERROR");
        error(() -> amounts.total(0, 0, true), 400, "VALIDATION_ERROR");
        error(() -> amounts.total(-100, 1, true), 400, "VALIDATION_ERROR");
    }
    @Test void wireDatesAndAmountsFollowExistingJsonConfiguration() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var tree = json.readTree(json.writeValueAsBytes(new CashReceipt(19, 37, LocalDate.of(2026, 10, 31), 110)));
        assertThat(tree.path("rcpt_dt").asText()).isEqualTo("2026-10-31");
        assertThat(tree.path("rcpt_amt").isIntegralNumber()).isTrue();
        assertThat(json.readTree(json.writeValueAsBytes(new Revenue("2026-10", 100))).path("month").asText())
            .isEqualTo("2026-10");
    }
    @ParameterizedTest @CsvSource({"createBilling,create", "updateBilling,update", "confirmBilling,confirm",
        "cancelBilling,cancel", "createBillingReceipt,receipt", "listBillings,read",
        "validateDispatchAttendanceUpload,upload"})
    void permissionKeysArePublicExtensionPoints(String operation, String action) {
        assertThat(BillingActions.key(operation)).isEqualTo("UI-13." + action);
        assertThat(BillingActions.can(new PermissionService(), operation)).isTrue();
        assertThat(BillingActions.reminderKey()).isEqualTo("UI-13.remind");
        assertThat(new CurrentUser().userId()).isNull();
        assertThat(new CurrentUser().dataScope()).isEqualTo(DataScope.ALL);
    }
}
