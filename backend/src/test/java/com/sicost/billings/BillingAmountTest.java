package com.sicost.billings;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.sicost.billings.BillingModels.*;

class BillingAmountTest {
    BillingMapper mapper = mock(BillingMapper.class);
    BillingAmountService service = new BillingAmountService(mapper);
    Source source(String kind, BigDecimal days, BigDecimal hours, BigDecimal denominator) {
        return new Source(2, 4, 4, "인력", kind, "2026-10", new BigDecimal("0.33"), 1001L,
            5L, days, hours, BigDecimal.ONE, denominator);
    }
    @Test void monthFloorsStoredMmWithoutProration() {
        when(mapper.sources(any())).thenReturn(List.of(source("NEW", null, null, null)));
        var result = service.calculate(new Project(1, "MO", "MONTH", "Y"), "2026-10", List.of());
        assertThat(result.supply()).isEqualTo(330); assertThat(result.details().get(0).mm_qty()).isEqualByComparingTo("0.33");
    }
    @Test void dayUsesAttendanceAndHalfUpMm() {
        when(mapper.sources(any())).thenReturn(List.of(source("NEW", new BigDecimal("10.5"), null, new BigDecimal("20"))));
        var result = service.calculate(new Project(1, "DP", "DAY", "Y"), "2026-10", List.of());
        assertThat(result.supply()).isEqualTo(10510); assertThat(result.details().get(0).mm_qty()).isEqualByComparingTo("0.53");
    }
    @Test void hourAddsManualWonWithoutMultiplierAndWarnsAboutMissingMm() {
        when(mapper.sources(any())).thenReturn(List.of(source("NEW", null, new BigDecimal("2.5"), new BigDecimal("20"))));
        var result = service.calculate(new Project(1, "DP", "HOUR", "Y"), "2026-10", List.of(new Overtime(2, 17)));
        assertThat(result.supply()).isEqualTo(2519); assertThat(result.details().get(0).mm_qty()).isNull();
        assertThat(result.details().get(0).ovtm_amt()).isEqualTo(17); assertThat(result.warnings()).hasSize(1);
    }
    @Test void freeAssignmentAlwaysHasZeroSupply() {
        when(mapper.sources(any())).thenReturn(List.of(source("FREE", null, null, null)));
        assertThat(service.calculate(new Project(1, "MO", "MONTH", "Y"), "2026-10", List.of()).supply()).isZero();
    }
    @Test void unknownOvertimeAssignmentIsRejected() {
        when(mapper.sources(any())).thenReturn(List.of(source("NEW", null, BigDecimal.ONE, BigDecimal.ONE)));
        assertThatThrownBy(() -> service.calculate(new Project(1, "DP", "HOUR", "Y"), "2026-10", List.of(new Overtime(99, 1))))
            .isInstanceOf(com.sicost.common.api.ApiException.class);
    }
}
