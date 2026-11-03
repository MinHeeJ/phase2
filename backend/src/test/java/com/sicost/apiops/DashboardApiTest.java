package com.sicost.apiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.*;
import com.sicost.businessplan.*;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import com.sicost.opportunitiesops.*;
import com.sicost.profit.MonthlyProfitService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=DashboardController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,DashboardQueryService.class,PermissionService.class})
class DashboardApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean MonthlyProfitService profit;
    @MockBean BusinessPlanService plans;
    @MockBean WorkforceForecastService workforce;
    @MockBean BillingReceivableService receivables;
    @MockBean ClosingQueryService closing;
    @MockBean Clock clock;
    @MockBean DashboardSections sections;
    @MockBean DashboardActionSources actionSources;
    List<Map<String,Object>> rows;
    static BigDecimal n(String value){return new BigDecimal(value);}
    static Map<String,Object> row(int year,int m,Long revenue,Long operating,boolean closed) {
        var row=new LinkedHashMap<String,Object>();row.put("month",YearMonth.of(year,m).toString());row.put("closed",closed);
        row.put("revenue",revenue);row.put("operating_profit",operating);row.put("cost",500000L);row.put("warnings",List.of());return row;
    }
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-29T00:00:00Z"));
        rows=new ArrayList<>();for(int m=1;m<=12;m++)rows.add(row(2026,m,m==9?1000000L:1200000L,200000L,false));
        when(profit.calculate(eq(2026),any())).thenAnswer(i->rows);
        when(plans.list(2026)).thenReturn(new BusinessPlanModels.Plans(java.util.stream.IntStream.rangeClosed(1,12)
            .mapToObj(m->new BusinessPlanModels.Plan(2026,m,null,1500000,250000,n("90"))).toList(),List.of(),List.of(),2026));
        when(closing.readDashboardClosing(anyString())).thenReturn(new ClosingQueryModels.DashboardClosing("OPEN","2026-08",List.of(),false));
        when(receivables.currentReceivables(any())).thenReturn(new BillingModels.CurrentReceivables(700000,2,500000));
        when(workforce.readUtilization(anyString(),any())).thenReturn(new OpportunityForecastModels.UtilizationRead(n("8"),n("10"),2));
        when(workforce.readSupplyDemand(any())).thenAnswer(i->{var q=i.getArgument(0,OpportunityForecastModels.Query.class);
            return new OpportunityForecastModels.WorkforcePage(List.of(),0,Integer.MAX_VALUE,0,List.of(),q.months(),90,null);});
    }
    @Test void anonymousDefaultUsesSeoulMonthAndAllFiveOwnerCards() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.basisMonth").value("2026-10"))
            .andExpect(jsonPath("$.data.kpis.revenue.amount").value(1200000))
            .andExpect(jsonPath("$.data.kpis.revenue.previous.changePercent").value(20.0))
            .andExpect(jsonPath("$.data.kpis.revenue.achievementPercent").value(80.0))
            .andExpect(jsonPath("$.data.kpis.operatingProfit.amount").value(200000))
            .andExpect(jsonPath("$.data.kpis.ytdOperatingProfit.amount").value(2000000))
            .andExpect(jsonPath("$.data.kpis.utilization.utilizationPercent").value(80.0))
            .andExpect(jsonPath("$.data.kpis.utilization.targetDifferencePp").value(-10.0))
            .andExpect(jsonPath("$.data.kpis.receivables.amount").value(700000));
        verify(receivables).currentReceivables(Instant.parse("2026-10-29T00:00:00Z"));
        verify(clock,times(1)).instant();
    }
    @Test void utcBoundaryDefaultsToSeoulNextMonth() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-30T15:00:00Z"));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk()).andExpect(jsonPath("$.data.basisMonth").value("2026-10"));
    }
    @ParameterizedTest @ValueSource(strings={"2026-13","2026-00","2026-1","0000-01",""," 2026-01","2026-01-01","abcd-ef","10000-01"})
    void malformedMonthIsInvalidNotDefaulted(String month) throws Exception {
        mvc.perform(get("/api/dashboard").param("month",month)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_MONTH")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(profit,plans,receivables,closing,workforce);
    }
    @Test void futureMonthIsRejectedBeforeOwners() throws Exception {
        mvc.perform(get("/api/dashboard").param("month","2026-11")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FUTURE_MONTH")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(profit,plans,receivables,closing,workforce);
    }
    @Test void pastBasisSeparatesActualCutoffAndTodayForecastHorizon() throws Exception {
        mvc.perform(get("/api/dashboard").param("month","2026-03")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.ytdOperatingProfit.amount").value(600000))
            .andExpect(jsonPath("$.data.kpis.receivables.basisDate").value("2026-10-29"))
            .andExpect(jsonPath("$.data.actions.basisDate").value("2026-10-29"))
            .andExpect(jsonPath("$.data.projectAlerts.basisDate").value("2026-10-29"))
            .andExpect(jsonPath("$.data.revenueCostTrend.months.length()").value(12))
            .andExpect(jsonPath("$.data.revenueCostTrend.months[3].revenue").isEmpty())
            .andExpect(jsonPath("$.data.revenueCostTrend.months[3].actualState").value("FUTURE"))
            .andExpect(jsonPath("$.data.revenueCostTrend.months[11].targetRevenue").value(1500000))
            .andExpect(jsonPath("$.data.workforceForecast.fromMonth").value("2026-11"))
            .andExpect(jsonPath("$.data.workforceForecast.toMonth").value("2027-04"))
            .andExpect(jsonPath("$.data.cashForecast.fromMonth").value("2026-11"))
            .andExpect(jsonPath("$.data.cashForecast.toMonth").value("2027-01"));
        verify(workforce).readSupplyDemand(argThat(q->q.size()==Integer.MAX_VALUE&&q.months().size()==6&&q.today().equals(LocalDate.of(2026,10,29))));
    }
    @Test void zeroRevenueAndPreviousZeroAreNullRatesNotMissingActuals() throws Exception {
        rows.set(8,row(2026,9,0L,0L,false));rows.set(9,row(2026,10,0L,100L,false));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.revenue.state").value("READY"))
            .andExpect(jsonPath("$.data.kpis.revenue.amount").value(0))
            .andExpect(jsonPath("$.data.kpis.revenue.previous.changePercent").isEmpty())
            .andExpect(jsonPath("$.data.kpis.operatingProfit.marginPercent").isEmpty());
    }
    @Test void negativePreviousProfitUsesAbsoluteDenominatorAndReferenceLabel() throws Exception {
        rows.set(8,row(2026,9,100L,-100L,false));rows.set(9,row(2026,10,100L,-50L,false));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.operatingProfit.previous.changePercent").value(50))
            .andExpect(jsonPath("$.data.kpis.operatingProfit.previous.isReferenceRate").value(true))
            .andExpect(jsonPath("$.data.kpis.operatingProfit.previous.label").value("참고 증감률"));
    }
    @Test void januaryComparisonReadsPreviousYearOnce() throws Exception {
        when(profit.calculate(eq(2025),any())).thenReturn(List.of(row(2025,12,1000000L,-100000L,true)));
        mvc.perform(get("/api/dashboard").param("month","2026-01")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.revenue.previous.changePercent").value(20));
        verify(profit,times(1)).calculate(2025,YearMonth.of(2026,10));
        verify(profit,times(1)).calculate(2026,YearMonth.of(2026,10));
    }
    @Test void missingTargetsAreNullNotZero() throws Exception {
        when(plans.list(2026)).thenReturn(new BusinessPlanModels.Plans(List.of(),List.of(),List.of(),2026));
        var body=json.readTree(mvc.perform(get("/api/dashboard")).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(body.at("/kpis/revenue").has("targetAmount")).isTrue();
        assertThat(body.at("/kpis/revenue/targetAmount").isNull()).isTrue();
        assertThat(body.at("/kpis/ytdOperatingProfit/targetAmount").isNull()).isTrue();
    }
    @Test void isolatedOwnerFailureRetainsHealthyCardsAndDoesNotLeakException() throws Exception {
        when(workforce.readUtilization(anyString(),any())).thenThrow(new IllegalStateException("private database detail"));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.utilization.state").value("ERROR"))
            .andExpect(jsonPath("$.data.kpis.utilization.confirmedMm").isEmpty())
            .andExpect(jsonPath("$.data.kpis.revenue.amount").value(1200000))
            .andExpect(jsonPath("$.data.kpis.receivables.amount").value(700000))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
    @Test void financeFailureDoesNotPoisonReceivablesOrWorkforce() throws Exception {
        when(profit.calculate(anyInt(),any())).thenThrow(new IllegalStateException("SQL"));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.revenue.state").value("ERROR"))
            .andExpect(jsonPath("$.data.kpis.receivables.state").value("READY"))
            .andExpect(jsonPath("$.data.workforceForecast.state").value("READY"));
    }
    @Test void missingClosedUtilizationNeverReadsLiveWorkforce() throws Exception {
        when(closing.readDashboardClosing("2026-10")).thenReturn(new ClosingQueryModels.DashboardClosing("CLOSED","2026-10",List.of(),true));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kpis.utilization.state").value("MISSING"))
            .andExpect(jsonPath("$.data.kpis.utilization.utilizationPercent").isEmpty())
            .andExpect(jsonPath("$.data.closeProgress.status").value("MISSING_CLOSED_SNAPSHOT"))
            .andExpect(jsonPath("$.data.closeProgress.completedCount").isEmpty());
        verify(workforce,never()).readUtilization(anyString(),any());
    }
    @Test void uncheckedOpenHasNoInventedNineFailures() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.closeProgress.status").value("NOT_CHECKED"))
            .andExpect(jsonPath("$.data.closeProgress.completedCount").value(0))
            .andExpect(jsonPath("$.data.closeProgress.totalCount").value(9))
            .andExpect(jsonPath("$.data.closeProgress.incompleteChecks").isEmpty());
    }
    @Test void storedSixOkTwoWarnOneFailCountsOnlyOk() throws Exception {
        var checks=java.util.stream.IntStream.range(0,9).mapToObj(i->new ClosingQueryModels.StoredCheck("CHECK"+i,i<6?"OK":i<8?"WARN":"FAIL",i<6?0:1,"점검",LocalDateTime.of(2026,10,28,12,0))).toList();
        when(closing.readDashboardClosing(anyString())).thenReturn(new ClosingQueryModels.DashboardClosing("OPEN","2026-08",checks,false));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk()).andExpect(jsonPath("$.data.closeProgress.completedCount").value(6))
            .andExpect(jsonPath("$.data.closeProgress.incompleteChecks.length()").value(3));
        verify(closing,never()).getClosing(anyString());
    }
    @ParameterizedTest @CsvSource({"2.4,false,0.4","2.5,true,0.5"})
    void aggregateWorkforceThresholdIsStrict(String demand,boolean shortage,String difference) throws Exception {
        var point=new OpportunityForecastModels.Month("2026-11","BE","S",n("2"),n(demand),n("0"),n("0"),n("2"),n("0"),n("0"),n("7"),null,0,true,null,0,List.of());
        doReturn(new OpportunityForecastModels.WorkforcePage(List.of(point),0,Integer.MAX_VALUE,1,List.of(),List.of("2026-11"),90,null)).when(workforce).readSupplyDemand(any());
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workforceForecast.months[0].availableMm").value(2))
            .andExpect(jsonPath("$.data.workforceForecast.months[0].shortageMm").value(Double.parseDouble(difference)))
            .andExpect(jsonPath("$.data.workforceForecast.months[0].isShortage").value(shortage));
    }
    @Test void actionKindTotalsIncludeUndeterminedAndNotAffectedCountSum() throws Exception {
        var body=json.readTree(mvc.perform(get("/api/dashboard")).andReturn().getResponse().getContentAsString()).at("/data/actions");
        assertThat(body.get("totalKinds").asInt()).isEqualTo(body.get("items").size());
        assertThat(body.get("items").get(0).get("affectedCount").asInt()).isEqualTo(2);
        assertThat(body.get("items").get(1).get("state").asText()).isEqualTo("UNDETERMINED");
        assertThat(body.get("items").get(1).get("affectedCount").isNull()).isTrue();
    }
}
