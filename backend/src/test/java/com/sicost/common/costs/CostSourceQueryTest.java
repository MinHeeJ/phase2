package com.sicost.common.costs;

import com.sicost.apiops.*;
import com.sicost.billingsops.*;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.sicost.apiops.ProjectBoardModels.*;
import static com.sicost.billingsops.BillingModels.*;

class CostSourceQueryTest {
    ProjectBoardMapper board;
    BillingMapper billing;
    @BeforeEach void setup() { board = mock(ProjectBoardMapper.class); billing = mock(BillingMapper.class); }
    @Test void businessAliasReadsExistingEffectiveNumericStandardWithoutInventingKey() {
        when(board.params("2026-10")).thenReturn(List.of(new OperatingParam("RENEGO_COST_RATIO", "2000-01", "기준",
            new BigDecimal("83"), null, "%", null)));
        assertThat(new CostStandards(board).effective("2026-10", "RENEG_COST_RATE")).isEqualByComparingTo("83");
        verify(board).params("2026-10");
    }
    @Test void missingNumericStandardNeverUsesTextOrHardcodedEighty() {
        when(board.params("2026-10")).thenReturn(List.of(new OperatingParam("RENEGO_COST_RATIO", "2000-01", "기준",
            null, "80", "%", null)));
        assertThatThrownBy(() -> new CostStandards(board).effective("2026-10", "RENEG_COST_RATE"))
            .isInstanceOf(com.sicost.common.api.ApiException.class);
    }
    @Test void projectReadDelegatesToBillingOwnerAndPreservesCompanyRevenueAndCash() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        var query = new BillingRevenueQuery(billing, mock(BillingReceivableService.class), clock, new PermissionService());
        var period = new RevenuePeriod(7, "2026-01", "2026-10");
        when(billing.projectRevenue(period)).thenReturn(List.of(new ProjectRevenue(7, "2026-10", -20)));
        when(billing.revenue(2026)).thenReturn(List.of(new Revenue("2026-10", 80)));
        when(billing.cash(2026)).thenReturn(List.of(new Cash(LocalDate.of(2026, 10, 1), 110)));
        assertThat(query.projectRevenue(7, YearMonth.of(2026, 1), YearMonth.of(2026, 10)))
            .containsExactly(new ProjectRevenue(7, "2026-10", -20));
        assertThat(query.revenue(2026)).containsExactly(new Revenue("2026-10", 80));
        assertThat(query.cash(2026)).containsExactly(new Cash(LocalDate.of(2026, 10, 1), 110));
    }
    @Test void reversedRevenuePeriodIsValidationError() {
        var query = new BillingRevenueQuery(billing, mock(BillingReceivableService.class), Clock.systemUTC(), new PermissionService());
        assertThatThrownBy(() -> query.projectRevenue(7, YearMonth.of(2026, 10), YearMonth.of(2026, 1)))
            .isInstanceOf(com.sicost.common.api.ApiException.class);
        verifyNoInteractions(billing);
    }
}
