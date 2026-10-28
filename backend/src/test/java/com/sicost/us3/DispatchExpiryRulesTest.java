package com.sicost.us3;

import static com.sicost.us3.DispatchExpiryRules.*;
import static org.assertj.core.api.Assertions.*;
import com.sicost.apiops.ProjectBoardModels.OperatingParam;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.DispatchExpiryCalculator;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class DispatchExpiryRulesTest {
    final LocalDate today = LocalDate.parse("2026-10-14");
    final LocalDate first = LocalDate.parse("2024-11-20");
    final Parameters parameters = new Parameters(24, List.of(60, 30));

    @Test void normalExpiryAndAlertDatesUseStrictExpression() {
        var result = display("DP", first, LocalDate.parse("2026-11-19"), today, parameters);
        assertThat(result.dispatch_expiry_dt()).isEqualTo("2026-11-19");
        assertThat(result.dispatch_display()).isEqualTo("파견 만료 D-36");
        assertThat(result.dispatch_tone()).isEqualTo("warning");
        assertThat(result.dispatch_alert_dates()).containsExactly(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-10-20"));
    }

    @Test void leapDayUsesD5NotContradictoryOriginalAc03() {
        assertThat(DispatchExpiryCalculator.expiry(LocalDate.parse("2024-02-29"), 24)).isEqualTo("2026-02-27");
        assertThat(DispatchExpiryCalculator.exceeds(LocalDate.parse("2024-02-29"), 24,
            LocalDate.parse("2026-02-28"))).isTrue();
    }

    @Test void extensionChangesDisplayNotExpiry() {
        var before = display("DP", first, LocalDate.parse("2026-11-19"), today, parameters);
        var after = display("DP", first, LocalDate.parse("2026-12-31"), today, parameters);
        assertThat(after.dispatch_expiry_dt()).isEqualTo(before.dispatch_expiry_dt());
        assertThat(after.dispatch_display()).isEqualTo("파견 기간 초과");
        assertThat(after.dispatch_tone()).isEqualTo("danger");
        assertThat(after.within60()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"MO", "TK", "IN"})
    void currentNonDispatchContractHasNoExpiryOrFilterMatch(String contract) {
        var result = display(contract, first, LocalDate.parse("2026-12-31"), today, parameters);
        assertThat(result.dispatch_display()).isEqualTo("–");
        assertThat(result.dispatch_first_dt()).isNull();
        assertThat(result.dispatch_expiry_dt()).isNull();
        assertThat(result.within60()).isFalse();
    }

    @Test void endedOverrunShowsOnlyDateAndIsExcluded() {
        var result = display("DP", LocalDate.parse("2023-01-01"), today.minusDays(1), today, parameters);
        assertThat(result.kind()).isEqualTo(Kind.DATE);
        assertThat(result.dispatch_display()).isEqualTo("2024-12-31");
        assertThat(result.within60()).isFalse();
    }

    @Test void expiredButNotEndedNeverShowsNegativeCountdown() {
        var result = display("DP", LocalDate.parse("2023-01-01"), today, today, parameters);
        assertThat(result.kind()).isEqualTo(Kind.OVERRUN);
        assertThat(result.dispatch_display()).doesNotContain("D--");
    }

    @ParameterizedTest @CsvSource({"0,danger,true", "30,danger,true", "31,warning,true", "60,warning,true", "61,neutral,false"})
    void countdownThresholdsAreInclusive(int days, String tone, boolean match) {
        LocalDate expiry = DispatchExpiryCalculator.expiry(first, parameters.months());
        var result = display("DP", first, expiry, expiry.minusDays(days), parameters);
        assertThat(result.dispatch_tone()).isEqualTo(tone);
        assertThat(result.within60()).isEqualTo(match);
        if (match) assertThat(result.dispatch_display()).isEqualTo("파견 만료 D-" + days);
    }

    @Test void overrunPrecedesCountdownEvenWhenExpiryIsFarAway() {
        var result = display("DP", first, LocalDate.parse("2026-12-31"), LocalDate.parse("2026-01-01"), parameters);
        assertThat(result.kind()).isEqualTo(Kind.OVERRUN);
    }

    @Test void databaseParametersHaveNoNumericAlertParsingOrHardcodedFallback() {
        var params = parameters(List.of(param("DISPATCH_MAX_MONTHS", "18", null),
            param("DISPATCH_ALERT_DAYS", null, "45, 15")));
        assertThat(params.months()).isEqualTo(18);
        assertThat(params.warningDay()).isEqualTo(45);
        assertThat(params.dangerDay()).isEqualTo(15);
        assertThat(display("DP", first, LocalDate.parse("2026-05-19"), LocalDate.parse("2026-04-20"), params)
            .dispatch_tone()).isEqualTo("warning");
    }

    @ParameterizedTest @ValueSource(strings = {"", "60,", "x,30", "60,-1", "30,30"})
    void invalidAlertListIsExplicitParameterError(String alerts) {
        assertThatThrownBy(() -> parameters(List.of(param("DISPATCH_MAX_MONTHS", "24", null),
            param("DISPATCH_ALERT_DAYS", null, alerts))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.field()).isEqualTo("month"));
    }

    @Test void absentStandardsDoNotInventValues() {
        assertThatThrownBy(() -> parameters(List.of())).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"false", "true"})
    void explicitBooleanWire(String value) {
        assertThat(dispatchWithin60(value == null ? Map.of() : Map.of("dispatchWithin60", value)))
            .isEqualTo("true".equals(value));
    }

    @ParameterizedTest @ValueSource(strings = {"TRUE", "False", "1", "0", "", "yes", " true"})
    void rejectsNonBooleanFilterWire(String value) {
        assertThatThrownBy(() -> dispatchWithin60(Map.of("dispatchWithin60", value)))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(400);
                assertThat(e.field()).isEqualTo("dispatchWithin60");
            });
    }

    static OperatingParam param(String key, String number, String text) {
        return new OperatingParam(key, "2000-01", key,
            number == null ? null : new BigDecimal(number), text, null, null);
    }
}
