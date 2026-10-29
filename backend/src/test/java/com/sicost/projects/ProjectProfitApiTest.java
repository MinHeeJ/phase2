package com.sicost.projects;

import com.sicost.apiops.ProjectCostService;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.billingsops.BillingModels.ProjectRevenue;
import com.sicost.common.api.*;
import com.sicost.common.costs.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.projects.ProjectProfitModels.*;
import static com.sicost.common.costs.CostAllocationModels.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=ProjectProfitController.class)
@Import({ProjectProfitService.class, PermissionService.class, ApiExceptionHandler.class,
    RequestIdFilter.class, ProjectProfitApiTest.Time.class})
class ProjectProfitApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean ProjectProfitMapper mapper;
    @MockBean ProjectCostService costs;
    @MockBean BillingRevenueQuery revenue;
    @MockBean CostStandards standards;
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static Source source(String type, Long budget, String target) {
        return new Source(7,"P-2607","원가 분석",type,"RUN",1L,"본사","담당 PM",500L,500L,budget,
            target == null ? null : n(target),n("50"),LocalDate.of(2026,1,1),LocalDate.of(2026,12,31));
    }
    @BeforeEach void setup() {
        var source=source("TK",400L,"20");
        when(mapper.project(7)).thenReturn(source);
        when(mapper.list(any())).thenReturn(List.of(source));
        when(mapper.plans(any())).thenReturn(List.of(new Plan("2026-10",n("2"))));
        when(mapper.people(7)).thenReturn(List.of(new Person(11,"개발자")));
        when(mapper.forecast(any())).thenReturn(List.of(new Forecast("2026-11",11L,n("1"),100L)));
        when(standards.effective(anyString(),eq("RENEG_COST_RATE"))).thenReturn(n("80"));
        when(standards.effective(anyString(),eq("TARGET_MARGIN_RATE"))).thenReturn(n("20"));
        var allocation=new Allocation(11L,7L,12L,"PROJECT",n("3.00"),n("3.00"),BigDecimal.ONE,
            300,20,400L,100L,false,false,false);
        when(costs.annualAllocations(eq(7L),any())).thenReturn(List.of(new Result("2026-10",false,
            List.of(allocation),List.of(),List.of(),List.of())));
        when(revenue.projectRevenue(eq(7L),any(),any())).thenReturn(List.of(new ProjectRevenue(7,"2026-10",200)));
    }
    @Test void anonymousListUsesConfirmedRevenueStoredBudgetAndActualCostExample() throws Exception {
        mvc.perform(get("/api/costs/projects").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data.items[0].revenue").value(200))
            .andExpect(jsonPath("$.data.items[0].cost").value(320))
            .andExpect(jsonPath("$.data.items[0].profit").value(-120))
            .andExpect(jsonPath("$.data.items[0].budget_rate").value(80.0))
            .andExpect(jsonPath("$.data.items[0].budget_alert").value("WARN"))
            .andExpect(jsonPath("$.data.items[0].forecast_cost").value(420))
            .andExpect(jsonPath("$.data.items[0].estimated_margin").value(16.0));
    }
    @Test void detailMonthlyTotalMatchesListAndKeepsReferenceRevenueSeparate() throws Exception {
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.cost").value(320))
            .andExpect(jsonPath("$.data.months[9].cost").value(320))
            .andExpect(jsonPath("$.data.months[9].revenue").value(200))
            .andExpect(jsonPath("$.data.months[9].reference_revenue").value(400))
            .andExpect(jsonPath("$.data.people[0].dev_nm").value("개발자"))
            .andExpect(jsonPath("$.data.period_note").value(org.hamcrest.Matchers.containsString("전체 누적")));
        verify(revenue).projectRevenue(7,YearMonth.of(2026,1),YearMonth.of(2026,10));
    }
    @Test void causesExposeOrderedExplanationNegativeResidualAndSafeNavigation() throws Exception {
        mvc.perform(get("/api/costs/projects/7/causes").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].code").value("STAFFING"))
            .andExpect(jsonPath("$.data.items[0].amount").value(106))
            .andExpect(jsonPath("$.data.primary_cause").value("STAFFING"))
            .andExpect(jsonPath("$.data.progress_excess").value(120))
            .andExpect(jsonPath("$.data.residual").value(14))
            .andExpect(jsonPath("$.data.items[0].route").value("/UI-11?projectId=7&year=2026"))
            .andExpect(jsonPath("$.data.items[1].route").isEmpty())
            .andExpect(jsonPath("$.data.items[1].unavailable").isNotEmpty());
    }
    @Test void internalProjectSuppressesRevenueAndMarginsAndShowsRnd() throws Exception {
        when(mapper.project(7)).thenReturn(source("IN",null,null));
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.revenue").isEmpty())
            .andExpect(jsonPath("$.data.summary.margin").isEmpty()).andExpect(jsonPath("$.data.summary.rnd_cost").value(320));
        verifyNoInteractions(revenue);
    }
    @Test void freeSupportAppearsOnceInCostAndNegativeResidualIsNotClamped() throws Exception {
        var free=new Allocation(11L,7L,12L,"PROJECT",n("0.50"),n("0.50"),n("0.5"),
            40,0,0L,null,false,true,false);
        when(costs.annualAllocations(eq(7L),any())).thenReturn(List.of(new Result("2026-10",false,
            List.of(free),List.of(),List.of(),List.of())));
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.cost").value(40))
            .andExpect(jsonPath("$.data.summary.free_cost").value(40))
            .andExpect(jsonPath("$.data.causes.residual").value(org.hamcrest.Matchers.lessThan(0)))
            .andExpect(jsonPath("$.data.causes.primary_cause").value("PRICING"));
    }
    @Test void absentForecastDoesNotInventCurrencyOrHideActualCost() throws Exception {
        when(mapper.forecast(any())).thenReturn(List.of(new Forecast("2026-11",null,n("2"),null)));
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.forecast_cost").isEmpty())
            .andExpect(jsonPath("$.data.summary.cost").value(320)).andExpect(jsonPath("$.data.summary.warnings").isNotEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"MO","DP"})
    void monthlyMarginUsesBasisMonthNotAnnualRevenueOrContract(String type) throws Exception {
        when(mapper.project(7)).thenReturn(source(type,400L,"20"));
        when(revenue.projectRevenue(eq(7L),any(),any())).thenReturn(List.of(new ProjectRevenue(7,"2026-01",1000),new ProjectRevenue(7,"2026-10",200)));
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.estimated_margin").value(-60));
        verify(mapper,never()).forecast(any());
    }
    @Test void zeroBudgetReturnsUnavailableWithWarningNotBudgetRateFallback() throws Exception {
        when(mapper.project(7)).thenReturn(source("TK",0L,"30"));
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.budget_rate").isEmpty())
            .andExpect(jsonPath("$.data.summary.budget_alert").value("UNAVAILABLE"));
    }
    @Test void combinedFiltersArePassedAsOneQueryAndTotalIsBeforePaging() throws Exception {
        mvc.perform(get("/api/costs/projects").param("year","2026").param("type","TK").param("partId","1")
            .param("status","RUN").param("q","원가").param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(1));
        verify(mapper).list(new Filter(2026,"TK",1L,"RUN","원가"));
    }
    @Test void emptyListReturnsZeroSummary() throws Exception {
        when(mapper.list(any())).thenReturn(List.of());
        mvc.perform(get("/api/costs/projects").param("year","2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.summary.revenue").value(0));
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "{\"personDelta\":1}", "[]", "not-json"})
    void unsupportedSimulationIsRejectedWithoutCalculationOrWrites(String body) throws Exception {
        mvc.perform(post("/api/costs/projects/7/simulate").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("승인")));
        verify(mapper).project(7);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(costs,revenue,standards);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","9223372036854775808"})
    void invalidPathKeysUseFeatureValidationError(String id) throws Exception {
        for (String suffix : List.of("","/causes")) mvc.perform(get("/api/costs/projects/"+id+suffix).param("year","2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("id"));
        mvc.perform(post("/api/costs/projects/"+id+"/simulate")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(mapper);
    }
    @Test void missingProjectIs404ForAllDetailOperations() throws Exception {
        for (String suffix : List.of("","/causes")) mvc.perform(get("/api/costs/projects/9"+suffix).param("year","2026"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/costs/projects/9/simulate")).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"year","page","size","sort","type","status","partId"})
    void invalidFiltersAre400BeforeQuery(String key) throws Exception {
        var request=get("/api/costs/projects");
        if (!key.equals("year")) request.param("year","2026");
        mvc.perform(request.param(key,"invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(mapper,costs,revenue);
    }
    @Test void missingYearIs400WithSingularField() throws Exception {
        mvc.perform(get("/api/costs/projects")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("year"));
    }
    @Test void closedSnapshotErrorPropagatesWithoutLiveFallback() throws Exception {
        when(costs.annualAllocations(eq(7L),any())).thenThrow(ApiException.conflict("VALIDATION_ERROR","마감 배분자료 없음","month"));
        mvc.perform(get("/api/costs/projects/7").param("year","2026")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("마감 배분자료 없음"));
        verifyNoInteractions(revenue);
    }
}
