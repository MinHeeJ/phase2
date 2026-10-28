package com.sicost.us3;

import static org.assertj.core.api.Assertions.assertThat;

import com.sicost.changes.DispatchExpiry;
import com.sicost.changes.DispatchExpiry.Display;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DispatchExpiryTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-10-14");
    private static final LocalDate START = LocalDate.parse("2024-11-20");

    private DispatchExpiry.Result evaluate(String contract, String start, String end) {
        return DispatchExpiry.evaluate(contract, LocalDate.parse(start), LocalDate.parse(end),
            TODAY, 24, 60, 30);
    }

    @Test void standardExpiryAndAlertDates() {
        var result = evaluate("DP", "2024-11-20", "2026-11-19");
        assertThat(result.expiry()).isEqualTo("2026-11-19");
        assertThat(result.daysRemaining()).isEqualTo(36L);
        assertThat(result.alertDate()).isEqualTo("2026-09-20");
        assertThat(result.urgentDate()).isEqualTo("2026-10-20");
        assertThat(result.display()).isEqualTo(Display.WARNING);
        assertThat(result.dispatchSoon()).isTrue();
    }

    @Test void extensionDoesNotChangeExpiryButIsExceeded() {
        var result = evaluate("DP", "2024-11-20", "2026-12-31");
        assertThat(result.expiry()).isEqualTo(DispatchExpiry.expiry(START, 24));
        assertThat(result.display()).isEqualTo(Display.EXCEEDED);
        assertThat(result.dispatchSoon()).isTrue();
    }

    @Test void leapDayIsLastDayOfTargetFebruary() {
        assertThat(DispatchExpiry.expiry(LocalDate.parse("2024-02-29"), 24))
            .isEqualTo("2026-02-28");
    }

    @ParameterizedTest @ValueSource(strings = {"MO", "TK", "IN"})
    void nonDispatchContractHasNoExpiry(String contract) {
        var result = evaluate(contract, "2024-11-20", "2026-12-31");
        assertThat(result.display()).isEqualTo(Display.NONE);
        assertThat(result.expiry()).isNull();
        assertThat(result.dispatchSoon()).isFalse();
    }

    @Test void completedRowsShowDateEvenWhenExceeded() {
        var result = evaluate("DP", "2023-01-01", "2026-10-13");
        assertThat(result.display()).isEqualTo(Display.DATE);
        assertThat(result.dispatchSoon()).isFalse();
    }

    @Test void dayThirtyIsDangerAndDaySixtyIsWarning() {
        var danger = evaluate("DP", "2024-11-14", "2026-11-13");
        var warning = evaluate("DP", "2024-12-14", "2026-12-13");
        assertThat(danger.daysRemaining()).isEqualTo(30L);
        assertThat(danger.display()).isEqualTo(Display.DANGER);
        assertThat(warning.daysRemaining()).isEqualTo(60L);
        assertThat(warning.display()).isEqualTo(Display.WARNING);
    }
}
