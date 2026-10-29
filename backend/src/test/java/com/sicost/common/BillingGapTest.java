package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BillingGapTest {
    final BillingRevenueQuery query = mock(BillingRevenueQuery.class);
    final BillingGapService service = new BillingGapService(query);
    final ProjectMonth month = new ProjectMonth(37, "2026-10");

    @ParameterizedTest @CsvSource({"1000000,1000000,맞음", "1000000,990000,맞음",
        "1000000,989999,적게 청구", "1000000,1010001,많이 청구",
        "10000000,9950000,맞음", "10000000,9949999,적게 청구"})
    void toleranceIsInclusiveAndUsesSupplyRatherThanVat(long basis, long confirmed, String judgment) {
        when(query.confirmedSupply(month)).thenReturn(new Supply(confirmed, 1));
        var result = service.compare(month, basis, false, false);
        assertThat(result.judgment()).isEqualTo(judgment);
        assertThat(result.difference_amt()).isEqualTo(basis - confirmed);
        verify(query).confirmedSupply(month);
    }
    @Test void noConfirmedBillsIsMissingEvenInsideTolerance() {
        when(query.confirmedSupply(month)).thenReturn(new Supply(0, 0));
        assertThat(service.compare(month, 1, false, false).judgment()).isEqualTo("청구 없음");
    }
    @Test void missingAttendanceWinsOverFreeOrBalanced() {
        when(query.confirmedSupply(month)).thenReturn(new Supply(0, 0));
        assertThat(service.compare(month, 0, true, true).judgment()).isEqualTo("근태 미입력");
    }
    @Test void freeOnlyHasDistinctZeroSupplyJudgment() {
        when(query.confirmedSupply(month)).thenReturn(new Supply(0, 0));
        assertThat(service.compare(month, 0, false, true).judgment()).isEqualTo("맞음(무상)");
    }
}
