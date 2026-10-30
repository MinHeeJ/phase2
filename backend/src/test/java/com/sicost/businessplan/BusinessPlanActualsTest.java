package com.sicost.businessplan;

import com.sicost.billingsops.BillingModels.*;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.people.PeopleCostService;
import com.sicost.people.PeopleModels.Summary;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import static com.sicost.businessplan.BusinessPlanModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BusinessPlanActualsTest {
    BusinessPlanMapper mapper;
    BillingRevenueQuery billing;
    PeopleCostService people;
    MonthlyProfitReader reader;
    BusinessPlanActualsService service;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        mapper=mock(BusinessPlanMapper.class);billing=mock(BillingRevenueQuery.class);people=mock(PeopleCostService.class);
        reader=mock(MonthlyProfitReader.class);
        ObjectProvider<MonthlyProfitReader> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(reader);
        service=new BusinessPlanActualsService(mapper,billing,people,provider,
            Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        when(mapper.closedMonths(2026)).thenReturn(List.of("2026-01"));
        when(people.monthlyUtilization(anyString(),nullable(Long.class))).thenReturn(
            new Summary(1,BigDecimal.ONE,BigDecimal.ONE,null,null,null,0,BigDecimal.valueOf(50),null,List.of()));
    }
    @Test void closedSnapshotWinsAndOpenDelegatesSharedMonthlyProfit() {
        when(mapper.closedActual("2026-01")).thenReturn(new ClosedActual(1,100L,20L));
        when(reader.read("2026-02")).thenReturn(new MonthlyProfitReader.Month("2026-02",200L,-10L,List.of("source warning")));
        var result=service.read(2026,null);
        assertThat(result.get(0).revenue()).isEqualTo(100);
        assertThat(result.get(0).operating_profit()).isEqualTo(20);
        assertThat(result.get(1).operating_profit()).isEqualTo(-10);
        assertThat(result.get(1).warnings()).contains("source warning");
        verify(reader,never()).read("2026-01");
    }
    @Test void missingClosedSnapshotHasNullsAndNeverReadsOpenSourceOrCostEngine() {
        var result=service.read(2026,null).get(0);
        assertThat(result.revenue()).isNull();assertThat(result.operating_profit()).isNull();
        assertThat(result.warnings()).contains("MISSING_CLOSED_SNAPSHOT: 2026-01");
        verify(reader,never()).read("2026-01");verify(people,never()).monthlyUtilization("2026-01",null);
    }
    @Test void futureMonthsDoNotCallLimitedCostOrProfitSources() {
        var result=service.read(2026,null);
        assertThat(result.get(2).revenue()).isNull();assertThat(result.get(11).utilization_rate()).isNull();
        verify(reader,never()).read("2026-03");verify(people,never()).monthlyUtilization("2026-03",null);
    }
    @Test void futureYearStillReturnsAllTwelveUncalculatedMonths() {
        var result=service.read(2027,null);
        assertThat(result).hasSize(12);assertThat(result).allSatisfy(a->assertThat(a.revenue()).isNull());
        verifyNoInteractions(people,reader,billing);
    }
    @Test void partRevenueUsesEffectiveHistoryNotCurrentProjectRepresentative() {
        when(billing.projectRevenue(new CostRevenueQuery(2026,null))).thenReturn(List.of(
            new ProjectRevenue(1,"2026-01",100),new ProjectRevenue(1,"2026-02",200)));
        when(mapper.attributions(2026)).thenReturn(List.of(new Attribution(1,"2026-01",7L),new Attribution(1,"2026-02",8L)));
        var result=service.read(2026,7L);
        assertThat(result.get(0).revenue()).isEqualTo(100);
        assertThat(result.get(1).revenue()).isZero();assertThat(result.get(1).operating_profit()).isNull();
        assertThat(result.get(1).warnings()).anyMatch(w->w.startsWith("MISSING_PART_PROFIT_SOURCE"));
        verifyNoInteractions(reader);
    }
    @Test void priorImportIsNotUsedInBusinessPlanComparison() {
        var result=service.read(2025,null);
        assertThat(result).allSatisfy(a->{assertThat(a.operating_profit()).isNull();assertThat(a.warnings()).anyMatch(w->w.startsWith("IMPORT_NOT_AVAILABLE"));});
        verifyNoInteractions(reader,people);verify(mapper,never()).closedActual(anyString());
    }
    @Test void unexpectedSharedSourceFailureIsNotTurnedIntoZero() {
        when(reader.read("2026-02")).thenThrow(new IllegalStateException("source unavailable"));
        assertThatThrownBy(()->service.read(2026,null)).isInstanceOf(IllegalStateException.class);
    }
}
