package com.sicost.common;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ClosingValues;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ClosingValuesTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-31T15:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest @NullSource
    @ValueSource(strings = {"", "2026-1", "2026-13", "0000-01", " 2026-10", "2026-10 ", "2026-10-01"})
    void malformedMonthKeepsClosingErrorAndField(String value) {
        assertThatThrownBy(() -> ClosingValues.month(value, "originalMonth"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(400);
                assertThat(ex.code()).isEqualTo("INVALID_MONTH");
                assertThat(ex.field()).isEqualTo("originalMonth");
            });
    }

    @Test void seoulMonthRollsOverBeforeUtcDate() {
        assertThat(ClosingValues.currentMonth(clock)).isEqualTo(YearMonth.of(2026, 11));
        assertThat(ClosingValues.applyMonth("2026-11", clock)).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test void readsAcceptFutureMonthWithoutApplyingConfirmationPolicy() {
        assertThat(ClosingValues.month("2027-01", "month")).isEqualTo(YearMonth.of(2027, 1));
    }

    @Test void confirmationAcceptsPastYearAndCurrentMonth() {
        assertThat(ClosingValues.confirmMonth("2025-12", clock)).isEqualTo(YearMonth.of(2025, 12));
        assertThat(ClosingValues.confirmMonth("2026-11", clock)).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test void futureConfirmationIsInvalidMonth() {
        assertThatThrownBy(() -> ClosingValues.confirmMonth("2026-12", clock))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(400);
                assertThat(ex.code()).isEqualTo("INVALID_MONTH");
            });
    }

    @ParameterizedTest @ValueSource(strings = {"2026-10", "2026-12", "2025-11"})
    void adjustmentCannotChoosePastOpenOrFutureApplyMonth(String month) {
        assertThatThrownBy(() -> ClosingValues.applyMonth(month, clock))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(409);
                assertThat(ex.code()).isEqualTo("CURRENT_MONTH_REQUIRED");
                assertThat(ex.field()).isEqualTo("applyMonth");
            });
    }
}
