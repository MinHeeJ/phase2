package com.sicost.common;

import com.sicost.billingsops.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.validation.CostValues;
import com.sicost.costallocation.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostContractTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-31T15:00:00Z"), ZoneOffset.UTC);

    @Test void requestedPeriodsUseSeoulClockAndIncludeMissingMonthsInAverageDivisor() {
        assertThat(CostValues.months(2026, clock)).hasSize(11).endsWith("2026-11");
        assertThat(CostValues.months(2025, clock)).hasSize(12).startsWith("2025-01").endsWith("2025-12");
        assertThat(CostValues.month("2026-11", clock)).isEqualTo(YearMonth.of(2026, 11));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 2027})
    void futureOrInvalidYearIsValidationError(int year) {
        assertThatThrownBy(() -> CostValues.year(year, clock)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test void readAndDownloadMetadataUseExistingOperationsWithoutInventingExportEndpoint() {
        var permissions = new PermissionService();
        for (String id : List.of("listCostPeople", "getCostPersonBreakdown", "listCostProjects", "getCostProject",
                "simulateCostProject", "getCostProjectCauses", "listPartSettlements", "getPartSettlement",
                "updatePartSettlementCard", "confirmPartSettlement", "cancelPartSettlement", "listCostRenegotiations",
                "getCostAllocation")) {
            assertThat(permissions.can(CostActions.key(id))).isTrue();
        }
        assertThat(CostActions.key("getCostAllocation")).isEqualTo("UI-04.read");
        assertThat(CostActions.downloadKey("listCostPeople")).isEqualTo("UI-03.download");
        assertThat(CostActions.downloadKey("listCostProjects")).isEqualTo("UI-04.download");
        assertThat(CostActions.downloadKey("listPartSettlements")).isEqualTo("UI-19.download");
        assertThat(CostActions.downloadKey("listCostRenegotiations")).isEqualTo("UI-32.download");
        assertThat(new CurrentUser().userId()).isNull();
        assertThatThrownBy(() -> CostActions.key("exportCostPeople")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void edgeFiveCompanyRevenueCashAndProjectRevenueRemainDistinctPaths() {
        var mapper = mock(BillingMapper.class);
        var query = new BillingRevenueQuery(mapper, mock(BillingReceivableService.class), clock, new PermissionService());
        when(mapper.revenue(2026)).thenReturn(List.of(new Revenue("2026-10", 900)));
        when(mapper.cash(2026)).thenReturn(List.of(new Cash(LocalDate.of(2026, 11, 1), 1100)));
        var scoped = new CostRevenueQuery(2026, 101L);
        when(mapper.projectRevenue(scoped)).thenReturn(List.of(new ProjectRevenue(101, "2026-10", 900)));
        assertThat(query.revenue(2026)).containsExactly(new Revenue("2026-10", 900));
        assertThat(query.cash(2026)).containsExactly(new Cash(LocalDate.of(2026, 11, 1), 1100));
        assertThat(query.projectRevenue(scoped)).containsExactly(new ProjectRevenue(101, "2026-10", 900));
        verify(mapper).revenue(2026);
        verify(mapper).cash(2026);
        verify(mapper).projectRevenue(scoped);
        verifyNoMoreInteractions(mapper);
    }

    @Test void projectRevenueRejectsFutureYearAndNonpositiveProjectBeforeQuery() {
        var mapper = mock(BillingMapper.class);
        var query = new BillingRevenueQuery(mapper, mock(BillingReceivableService.class), clock, new PermissionService());
        assertThatThrownBy(() -> query.projectRevenue(new CostRevenueQuery(2027, 1L))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> query.projectRevenue(new CostRevenueQuery(2026, 0L))).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    @Test void authoritativeCostAndAdapterShareAllocationNeverLegacySql() {
        var allocations = mock(CostAllocationService.class);
        var row = CostAllocationApiTest.snapshot();
        var result = new CostAllocationModels.Allocation("2026-09", "2026-10", true, "원",
            List.of("급여 미업로드: dev_id=7"), List.of(row), 0);
        when(allocations.projectThrough(101, "2026-10")).thenReturn(List.of(result));
        var owner = new com.sicost.apiops.ProjectCostService(allocations);
        var adapter = new com.sicost.projectcost.ProjectCostService(owner);
        assertThat(owner.calculate(101, "2026-10").cumulative_cost_amt()).isEqualTo(15);
        assertThat(adapter.calculate(101, YearMonth.of(2026, 10)).cumulativeCostAmount()).isEqualTo(15);
        assertThat(adapter.calculate(101, YearMonth.of(2026, 10)).missingSalaryMonths()).containsExactly("2026-09");
    }

    @Test void closedSnapshotFailurePropagatesThroughAuthoritativeCostInsteadOfFallback() {
        var allocations = mock(CostAllocationService.class);
        when(allocations.projectThrough(101, "2026-10")).thenThrow(ApiException.notFound("마감 배분자료 없음"));
        assertThatThrownBy(() -> new com.sicost.apiops.ProjectCostService(allocations).calculate(101, "2026-10"))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("NOT_FOUND"));
    }
}
