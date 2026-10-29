package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.BillingValues;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BillingValuesTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    void invalid(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.field()).isEqualTo(field);
        });
    }

    @Test void exactWonPreservesBothBigintLimits() {
        assertThat(BillingValues.money(new BigDecimal("9223372036854775807"), Long.MIN_VALUE, "sply_amt"))
            .isEqualTo(Long.MAX_VALUE);
        assertThat(BillingValues.money(new BigDecimal("-9223372036854775808"), Long.MIN_VALUE, "sply_amt"))
            .isEqualTo(Long.MIN_VALUE);
    }

    @ParameterizedTest @ValueSource(strings = {"1.01", "9223372036854775808", "-9223372036854775809"})
    void fractionalOrOverflowWonIsExplicitBillingValidation(String amount) {
        invalid(() -> BillingValues.money(new BigDecimal(amount), Long.MIN_VALUE, "sply_amt"), "sply_amt");
    }

    @Test void missingAndBelowMinimumWonAreInvalid() {
        invalid(() -> BillingValues.money((BigDecimal) null, 1, "rcpt_amt"), "rcpt_amt");
        invalid(() -> BillingValues.money(BigDecimal.ZERO, 1, "rcpt_amt"), "rcpt_amt");
    }

    @ParameterizedTest @ValueSource(strings = {"1.2", "\"100\"", "true", "null", "9223372036854775808"})
    void jsonWonDoesNotCoerceFractionsStringsOrOverflow(String raw) throws Exception {
        var node = json.readTree(raw);
        invalid(() -> BillingValues.money(node, 0, "vat_amt"), "vat_amt");
    }

    @Test void jsonIntegerRemainsANumber() throws Exception {
        assertThat(BillingValues.money(json.readTree("123456789"), 0, "sply_amt")).isEqualTo(123456789L);
    }

    @ParameterizedTest @ValueSource(strings = {"2026-1", "2026-13", "0000-01", " 2026-10"})
    void monthNeverNormalizesMalformedInput(String month) {
        invalid(() -> BillingValues.month(month, "month"), "month");
    }

    @ParameterizedTest @ValueSource(strings = {"2026-02-29", "2026-1-01", "0000-01-01", "2026-10-01T00:00:00"})
    void dateRequiresRealIsoCalendarDay(String date) {
        invalid(() -> BillingValues.date(date, "bill_dt"), "bill_dt");
    }

    @Test void sourceAndVatRoundDownToWonAndManualVatWins() {
        assertThat(BillingValues.floorWon(new BigDecimal("123.999"), "sply_amt")).isEqualTo(123);
        assertThat(BillingValues.vat(199, new BigDecimal("7.5"), null)).isEqualTo(14);
        assertThat(BillingValues.vat(199, null, null)).isEqualTo(19);
        assertThat(BillingValues.vat(199, BigDecimal.ZERO, null)).isZero();
        assertThat(BillingValues.vat(199, new BigDecimal("7.5"), 3L)).isEqualTo(3);
        assertThat(BillingValues.vat(-199, new BigDecimal("7.5"), null)).isEqualTo(-15);
    }

    @Test void totalOverflowIs400InsteadOfWrappingBeforeDatabaseGeneratedTotal() {
        invalid(() -> BillingValues.add(Long.MAX_VALUE, 1, "tot_amt"), "tot_amt");
        invalid(() -> BillingValues.floorWon(new BigDecimal("9223372036854775808"), "sply_amt"), "sply_amt");
    }

    @Test void monthAndYearDefaultsUseExistingSeoulClockPolicy() {
        Clock clock = Clock.fixed(Instant.parse("2025-12-31T15:00:00Z"), ZoneOffset.UTC);
        assertThat(BillingValues.year(null, clock)).isEqualTo(2026);
        assertThat(BillingValues.defaultMonth(null, clock)).isEqualTo(YearMonth.of(2026, 1));
        assertThat(BillingValues.defaultMonth("2025-09", clock)).isEqualTo(YearMonth.of(2025, 9));
        assertThat(BillingValues.year(2024, clock)).isEqualTo(2024);
        invalid(() -> BillingValues.year(0, clock), "year");
    }
}
