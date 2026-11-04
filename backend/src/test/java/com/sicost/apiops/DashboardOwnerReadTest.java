package com.sicost.apiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.*;
import com.sicost.businessplan.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.common.security.PermissionService;
import com.sicost.costallocation.*;
import com.sicost.opportunitiesops.*;
import com.sicost.profit.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardOwnerReadTest {
    Clock clock=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    MonthlyProfitMapper mapper;
    BillingRevenueQuery billing;
    CostAllocationService costs;
    MonthlyProfitService finance;
    @BeforeEach void setup() {
        mapper=mock(MonthlyProfitMapper.class);billing=mock(BillingRevenueQuery.class);costs=mock(CostAllocationService.class);
        finance=new MonthlyProfitService(mapper,billing,costs,new ExcelExportService(),new PermissionService(),clock);
        when(mapper.closedMonths(anyInt())).thenReturn(List.of("2026-10"));
        when(mapper.facts(anyString())).thenReturn(new MonthlyProfitModels.Facts(1,0));
        when(mapper.annualTax(anyInt())).thenReturn(120L);
        when(costs.calculate(anyString(),isNull())).thenAnswer(i->new CostAllocationModels.Allocation(i.getArgument(0),"2026-10",false,"원",List.of(),List.of(),0));
        when(mapper.snapshot(any())).thenReturn(Map.of("revenue",1200000L,"operating_profit",200000L,"cost",1000000L));
    }
    DashboardQueryService dashboard() {
        var plan=mock(BusinessPlanService.class);when(plan.list(2026)).thenReturn(new BusinessPlanModels.Plans(List.of(),List.of(),List.of(),2026));
        var workforce=mock(WorkforceForecastService.class);
        when(workforce.readSupplyDemand(any())).thenAnswer(i->{var q=i.getArgument(0,OpportunityForecastModels.Query.class);return new OpportunityForecastModels.WorkforcePage(List.of(),0,Integer.MAX_VALUE,0,List.of(),q.months(),90,null);});
        var receipts=mock(BillingReceivableService.class);when(receipts.currentReceivables(any())).thenReturn(new BillingModels.CurrentReceivables(0,0,0));
        var closing=mock(ClosingQueryService.class);when(closing.readDashboardClosing(anyString())).thenReturn(new ClosingQueryModels.DashboardClosing("CLOSED","2026-10",List.of(),true));
        var query=new DashboardQueryService(finance,plan,workforce,receipts,closing,clock);
        var sections=mock(DashboardSections.class);
        when(sections.parts(any())).thenReturn(new DashboardModels.PartSummary(
            new DashboardModels.CardState("MISSING","마감 자료 확인 필요",null),"2026-10","파트 이익률",List.of()));
        query.connect(sections,mock(DashboardActionSources.class));
        return query;
    }
    @Test void closedKpiAndTrendIgnoreLaterMutableRevenueAndCosts() {
        var service=dashboard();var first=service.getDashboard("2026-10");
        when(billing.revenue(2026)).thenReturn(List.of(new BillingModels.Revenue("2026-10",999999999)));
        var next=service.getDashboard("2026-10");
        assertThat(next.kpis().revenue()).isEqualTo(first.kpis().revenue());
        assertThat(next.kpis().operatingProfit()).isEqualTo(first.kpis().operatingProfit());
        assertThat(next.revenueCostTrend().months().get(9)).isEqualTo(first.revenueCostTrend().months().get(9));
        assertThat(next.kpis().utilization().card().state()).isEqualTo("MISSING");
        assertThat(next.partSummary().card().state()).isEqualTo("MISSING");
        verify(costs,never()).calculate("2026-10",null);
    }
    @Test void missingClosedMoneyStaysNullWithoutLiveReplacement() {
        when(mapper.snapshot(any())).thenReturn(null);
        var result=dashboard().getDashboard("2026-10");
        assertThat(result.kpis().revenue().amount()).isNull();
        assertThat(result.kpis().revenue().card().message()).isEqualTo("마감 자료 확인 필요");
        assertThat(result.revenueCostTrend().months().get(9).revenue()).isNull();
        verify(costs,never()).calculate("2026-10",null);
    }
    @Test void capturedMonthOverloadMatchesPriorOwnerOutputForSameClock() {
        assertThat(finance.calculate(2026,YearMonth.of(2026,10))).isEqualTo(finance.calculate(2026));
    }
    @Test void supplyDemandProjectionMatchesExistingOwnerMmAndAvoidsFinancialNPlusOne() {
        var source=mock(OpportunityForecastMapper.class);var profit=mock(MonthlyProfitReader.class);
        when(source.developers(anyString())).thenReturn(List.of(new OpportunityForecastModels.Developer(1,"BE","S","FT",null,null,1000000L)));
        when(source.needs()).thenReturn(List.of(new OpportunityForecastModels.Demand(9,"BE","S","2026-11",1,1,new BigDecimal("2.5"),new BigDecimal("100"))));
        when(source.temporary()).thenReturn(List.of(new OpportunityForecastModels.Temporary("BE","S","2026-11","2026-11",new BigDecimal("1"))));
        var owner=new WorkforceForecastService(source,profit,clock);
        var query=new OpportunityForecastModels.Query(List.of("2026-11"),null,null,"month,asc",0,Integer.MAX_VALUE,LocalDate.of(2026,10,29));
        var lightweight=owner.readSupplyDemand(query).items().get(0);
        verifyNoInteractions(profit);
        var existing=owner.forecast(query).items().get(0);
        assertThat(lightweight.supply_mm()).isEqualByComparingTo(existing.supply_mm());
        assertThat(lightweight.demand_mm()).isEqualByComparingTo(existing.demand_mm());
        assertThat(lightweight.available_mm()).isEqualByComparingTo(existing.available_mm());
        assertThat(lightweight.supply_mm()).isEqualByComparingTo("1");
    }
    @Test void selectedMonthUtilizationUsesConfirmedMmNotHeadcountAndNoTemporaryOverlay() {
        var mapper=mock(OpportunityForecastMapper.class);var owner=new WorkforceForecastService(mapper,mock(MonthlyProfitReader.class),clock);
        when(mapper.developers(anyString())).thenReturn(List.of(new OpportunityForecastModels.Developer(1,"BE","S","FT",null,null,null),new OpportunityForecastModels.Developer(2,"BE","S","FT",null,null,null)));
        when(mapper.assignments()).thenReturn(List.of(new OpportunityForecastModels.Assignment(3,9,1,"BE","S",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),null)));
        when(mapper.assignmentMonths()).thenReturn(List.of(new OpportunityForecastModels.AssignedMonth(3,"2026-10",new BigDecimal("0.5"),1000L)));
        var result=owner.readUtilization("2026-10",LocalDate.of(2026,10,29));
        assertThat(result.confirmedMm()).isEqualByComparingTo("0.5");assertThat(result.availableMm()).isEqualByComparingTo("2");
        assertThat(result.idleDeveloperCount()).isEqualTo(1);
    }
    @Test void frozenClosingReadsHistoryNotMutableLatestChecks() {
        var mapper=mock(ClosingQueryMapper.class);var owner=new ClosingQueryService(mapper,new ObjectMapper());
        when(mapper.state("2026-10")).thenReturn(new ClosingQueryModels.State("CLOSED",LocalDateTime.of(2026,10,28,12,0),null,null));
        var checks=java.util.stream.IntStream.range(0,9).mapToObj(i->new ClosingQueryModels.StoredCheck("C"+i,"OK",0,"보존",LocalDateTime.of(2026,10,28,11,0))).toList();
        when(mapper.frozenChecks("2026-10")).thenReturn(checks);
        var first=owner.readDashboardClosing("2026-10");
        when(mapper.checks("2026-10")).thenReturn(List.of(new ClosingQueryModels.StoredCheck("C0","FAIL",8,"변경",LocalDateTime.now(clock))));
        assertThat(owner.readDashboardClosing("2026-10")).isEqualTo(first);
        assertThat(first.missingFrozenChecks()).isFalse();verify(mapper,never()).checks(anyString());
    }
    static BillingModels.State bill(long id,long adjusted,long received,LocalDate due) {
        return new BillingModels.State(id,"B-"+id,9,null,"2025-12",LocalDate.of(2025,12,1),1000,100,1100,"CONFIRM",null,null,due,"N",null,null,null,"프로젝트",null,null,"MO",null,null,received,adjusted);
    }
    @Test void allDateReceivablesReuseAmendmentBalanceAndStrictOverdueToday() {
        var mapper=mock(BillingMapper.class);var owner=new BillingReceivableService(mapper,clock,new PermissionService());
        var old=bill(1,1100,400,LocalDate.of(2026,10,28));var today=bill(2,100,0,LocalDate.of(2026,10,29));var negative=bill(3,50,100,LocalDate.of(2026,10,1));
        when(mapper.statesThrough(any())).thenReturn(List.of(old,today,negative));
        var result=owner.currentReceivables(clock.instant());
        assertThat(result.amount()).isEqualTo(800);assertThat(result.overdueCount()).isEqualTo(1);assertThat(result.overdueAmount()).isEqualTo(700);
        assertThat(owner.row(old).receivable_amt()).isEqualTo(700);
        verify(mapper).statesThrough(new BillingModels.ReceivableCutoff(LocalDate.of(2026,10,29),LocalDateTime.of(2026,10,29,9,0)));
        verify(mapper,never()).states(anyInt());
    }
}
