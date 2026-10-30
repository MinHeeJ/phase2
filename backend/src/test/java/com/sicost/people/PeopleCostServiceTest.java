package com.sicost.people;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.sicost.common.security.PermissionService;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.costallocation.CostAllocationModels.Allocation;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import org.junit.jupiter.api.*;
import static com.sicost.people.PeopleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PeopleCostServiceTest {
    PeopleMapper mapper;
    CostAllocationService allocation;
    PeopleCostService service;
    Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    @BeforeEach void setup() {
        mapper = mock(PeopleMapper.class);
        allocation = mock(CostAllocationService.class);
        service = new PeopleCostService(mapper, allocation, clock, new PermissionService());
    }
    Query query(Map<String,String> extra) {
        var q = new HashMap<String,String>(extra);
        q.putIfAbsent("year", "2026");
        return PeopleInput.query(q, clock);
    }
    void sources(List<PersonMonth> people, List<AllocationRow> january, List<AllocationRow> february) {
        when(mapper.people(any())).thenReturn(people);
        when(mapper.projects(any())).thenReturn(List.of(new ProjectLink(9, 1, 7, "업무", "MO", "DEV", "2026-01-01", "2026-02-28")));
        when(allocation.year(2026, null)).thenReturn(List.of(
            new Allocation("2026-01", "2026-02", false, "원", List.of(), january, 0),
            new Allocation("2026-02", "2026-02", false, "원", List.of(), february, 0)));
    }
    @Test void zeroDirectDenominatorAndNoPricesReturnNullNotInventedHundredPercent() {
        sources(List.of(), List.of(), List.of());
        var result = service.list(query(Map.of()));
        assertThat(result.summary().utilization_rt()).isNull();
        assertThat(result.summary().earning_rt()).isNull();
        assertThat(result.summary().avg_price_amt()).isNull();
        assertThat(result.summary().avg_cost_amt()).isNull();
    }
    @Test void leaveStaysInUtilizationDenominatorButNotPerPersonCostAndProfitAverage() {
        sources(List.of(PeopleApiTest.person(1, "2026-01", false, true), PeopleApiTest.person(1, "2026-02", false, true),
            PeopleApiTest.person(2, "2026-01", true, true), PeopleApiTest.person(2, "2026-02", true, true)),
            List.of(PeopleApiTest.amount(1, "2026-01", 9L, "PROJECT", 100, 200)),
            List.of(PeopleApiTest.amount(1, "2026-02", 9L, "PROJECT", 100, 200)));
        var summary = service.list(query(Map.of())).summary();
        assertThat(summary.psn_cnt()).isEqualTo(2);
        assertThat(summary.utilization_rt()).isEqualByComparingTo("50.0");
        assertThat(summary.avg_profit_amt()).isEqualTo(200);
        assertThat(summary.avg_cost_amt()).isEqualTo(200);
        assertThat(summary.contributions().get(0).contribution_amt()).isEqualTo(200);
    }
    @Test void monthlyRatesAreAveragedRatherThanPoolingPeopleAcrossMonths() {
        sources(List.of(PeopleApiTest.person(1, "2026-01", false, true), PeopleApiTest.person(1, "2026-02", false, true),
            PeopleApiTest.person(2, "2026-02", true, true)),
            List.of(PeopleApiTest.amount(1, "2026-01", 9L, "PROJECT", 100, 200)),
            List.of(PeopleApiTest.amount(1, "2026-02", 9L, "PROJECT", 100, 200)));
        assertThat(service.list(query(Map.of())).summary().utilization_rt()).isEqualByComparingTo("75.0");
    }
    @Test void turnkeyExclusionRetainsCostAndNegativeProfitButDropsPriceDenominator() {
        sources(List.of(PeopleApiTest.person(1, "2026-01", false, true)),
            List.of(PeopleApiTest.amount(1, "2026-01", 9L, "PROJECT", 100, 500)), List.of());
        when(mapper.projects(any())).thenReturn(List.of(new ProjectLink(9, 1, 7, "턴키", "TK", "DEV", "2026-01-01", "2026-01-31")));
        var result = service.list(query(Map.of("excludeTurnkeyPrice", "true")));
        assertThat(result.items().get(0).prft_amt()).isEqualTo(-100);
        assertThat(result.summary().avg_price_amt()).isNull();
        assertThat(result.summary().avg_profit_amt()).isEqualTo(-100);
    }
    @Test void zeroPriceIsIncludedInPriceAverage() {
        sources(List.of(PeopleApiTest.person(1, "2026-01", false, true)),
            List.of(PeopleApiTest.amount(1, "2026-01", 9L, "PROJECT", 100, 0)), List.of());
        assertThat(service.list(query(Map.of())).summary().avg_price_amt()).isZero();
    }
    @Test void summaryUsesEntireMatchingPopulationBeforePaginationAndStableIdentityTies() {
        var people = new ArrayList<PersonMonth>();
        for (long id = 25; id > 0; id--) people.add(PeopleApiTest.person(id, "2026-01", false, true));
        sources(people, List.of(), List.of());
        var result = service.list(query(Map.of("size", "20", "sort", "tot_cst_amt,desc")));
        assertThat(result.total()).isEqualTo(25);
        assertThat(result.summary().psn_cnt()).isEqualTo(25);
        assertThat(result.items()).hasSize(20);
        assertThat(result.items().get(0).dev_id()).isEqualTo(1);
        assertThat(service.full(query(Map.of("size", "20"))).items()).hasSize(25);
    }
    @Test void pastYearAverageDividesByTwelveIncludingMissingMonths() {
        when(mapper.people(any())).thenReturn(List.of(PeopleApiTest.person(1, "2025-01", false, true)));
        when(allocation.year(2025, null)).thenReturn(List.of());
        var row = service.list(query(Map.of("year", "2025", "view", "AVERAGE"))).items().get(0);
        assertThat(row.slry_amt()).isEqualTo(8);
        assertThat(row.period()).isEqualTo("2025-01 ~ 2025-12");
    }
    @Test void closedTotalsAreSnapshotOwnedEvenWhenCurrentPayrollDiffers() {
        sources(List.of(PeopleApiTest.person(1, "2026-01", false, true)), List.of(), List.of());
        when(allocation.year(2026, null)).thenReturn(List.of(new Allocation("2026-01", "2026-02", true, "원", List.of(),
            List.of(PeopleApiTest.amount(1, "2026-01", 9L, "PROJECT", 80, 200)), 0)));
        assertThat(service.list(query(Map.of())).items().get(0).tot_cst_amt()).isEqualTo(80);
    }
    @Test void unallocatedFreelancerCostIsNotDiscarded() {
        sources(List.of(new PersonMonth(1, "프리", null, null, null, "FL", "DIRECT", null, "DEV", null,
            "2026-01", false, true, 100, 0, 0, 0, 0, 100, 0, 100, 0, 0)), List.of(), List.of());
        assertThat(service.list(query(Map.of())).items().get(0).tot_cst_amt()).isEqualTo(100);
    }
}
