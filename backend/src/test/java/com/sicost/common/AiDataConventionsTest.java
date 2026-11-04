package com.sicost.common;

import com.sicost.common.ai.AiDataConventions;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AiDataConventionsTest {
    @Test void nullMissingDataAndActualZeroAreDifferent() {
        assertThat(AiDataConventions.won(null)).isNull();
        assertThat(AiDataConventions.won(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(AiDataConventions.ratioPercent(null, BigDecimal.ONE)).isNull();
        assertThat(AiDataConventions.ratioPercent(BigDecimal.ZERO, BigDecimal.ONE)).isEqualByComparingTo("0.00");
        assertThat(AiDataConventions.ratioPercent(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
    }
    @Test void amountMmAndRatiosRoundHalfUpNotFloatingPoint() {
        assertThat(AiDataConventions.won(new BigDecimal("10.5"))).isEqualByComparingTo("11");
        assertThat(AiDataConventions.won(new BigDecimal("-10.5"))).isEqualByComparingTo("-11");
        assertThat(AiDataConventions.twoDecimals(new BigDecimal("0.005"))).isEqualByComparingTo("0.01");
        assertThat(AiDataConventions.ratioPercent(BigDecimal.ONE, new BigDecimal("6"))).isEqualByComparingTo("16.67");
    }
    @Test void monthUsesExistingCalendarValidationAndSeoulClock() {
        Clock clock = Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"), ZoneOffset.UTC);
        assertThat(AiDataConventions.currentMonth(clock)).isEqualTo(YearMonth.of(2027, 1));
        assertThat(AiDataConventions.month("2027-01", "baseYm")).isEqualTo(YearMonth.of(2027, 1));
        assertThatThrownBy(() -> AiDataConventions.month("2027-13", "baseYm")).isInstanceOf(ApiException.class);
    }
    @Test void forecastTypeMapsPublicOperatingProfitToExistingPhysicalCode() {
        assertThat(AiDataConventions.forecastCode("OPERATING_PROFIT")).isEqualTo("OP_PROFIT");
        assertThat(AiDataConventions.forecastCode("REVENUE")).isEqualTo("REVENUE");
        assertThatThrownBy(() -> AiDataConventions.forecastCode("SQL")).isInstanceOf(ApiException.class);
    }
    @Test void requestTypeAddsNextWithoutChangingLegacyMeanings() {
        for (String type : new String[]{"PROJECT", "OPP", "TEXT", "NEXT"})
            assertThat(AiDataConventions.requestType(type)).isEqualTo(type);
        assertThatThrownBy(() -> AiDataConventions.requestType("TEAM")).isInstanceOf(ApiException.class);
    }
    @Test void aDecisionWirePendingDoesNotEnterExistingNoneCheck() {
        assertThat(AiDataConventions.aDecisionToWire("NONE")).isEqualTo("PENDING");
        assertThat(AiDataConventions.aDecisionToWire("ADOPT")).isEqualTo("ADOPT");
        assertThatThrownBy(() -> AiDataConventions.aDecisionToWire("PENDING")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cashAndRevenueTaxBasisAndOpenClosedLabelsAreExplicit() {
        assertThat(AiDataConventions.vatIncluded("CASH")).isTrue();
        assertThat(AiDataConventions.vatIncluded("REVENUE")).isFalse();
        assertThat(AiDataConventions.vatIncluded("COST")).isFalse();
        assertThat(AiDataConventions.closeLabel("OPEN")).isEqualTo("잠정");
        assertThat(AiDataConventions.closeLabel("CLOSED")).isEqualTo("확정");
    }
    @Test void independentVirtualExampleIsTwelvePercentAndInputsHaveRedactedToString() {
        var input = new AiDataConventions.VirtualInput(new BigDecimal("100000000"), new BigDecimal("80000000"), new BigDecimal("10"));
        var result = AiDataConventions.simulate(input);
        assertThat(result.cost()).isEqualByComparingTo("88000000");
        assertThat(result.profit()).isEqualByComparingTo("12000000");
        assertThat(result.marginPercent()).isEqualByComparingTo("12.00");
        assertThat(input.toString()).doesNotContain("100000000", "80000000");
        assertThat(result.toString()).doesNotContain("88000000", "12000000");
    }
    @Test void zeroRevenueProducesUndefinedMarginNotFabricatedPercent() {
        var result = AiDataConventions.simulate(new AiDataConventions.VirtualInput(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(result.marginPercent()).isNull();
        assertThat(result.profit()).isEqualByComparingTo("0");
    }
    @ParameterizedTest @ValueSource(strings={"-50.01", "100.01", "0.001"})
    void invalidVirtualChangesAreNotSilentlyRounded(String change) {
        assertThatThrownBy(() -> AiDataConventions.simulate(new AiDataConventions.VirtualInput(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal(change))))
            .isInstanceOf(ApiException.class);
    }
    @Test void negativeAndFractionalWonInputsAreRejected() {
        assertThatThrownBy(() -> AiDataConventions.simulate(new AiDataConventions.VirtualInput(new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ZERO))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AiDataConventions.simulate(new AiDataConventions.VirtualInput(BigDecimal.ONE, new BigDecimal("1.1"), BigDecimal.ZERO))).isInstanceOf(ApiException.class);
    }
}
