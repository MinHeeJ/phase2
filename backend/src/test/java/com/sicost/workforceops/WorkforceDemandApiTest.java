package com.sicost.workforceops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.common.WorkforcePlanningAssertions;
import com.sicost.opportunitiesops.*;
import java.math.*;
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
import static com.sicost.opportunitiesops.OpportunityForecastModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=WorkforceDemandController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,WorkforceForecastService.class,WorkforceDemandApiTest.Time.class})
class WorkforceDemandApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WorkforceForecastService service;
    @Autowired Clock clock;
    @MockBean OpportunityForecastMapper mapper;
    @MockBean MonthlyProfitReader profit;
    @BeforeEach void setup(){
        when(mapper.code(any())).thenReturn(true);
        when(mapper.developers(any())).thenReturn(List.of(new Developer(1,"BE","S","FT",null,null,10000000L)));
    }
    private com.fasterxml.jackson.databind.JsonNode getData(Map<String,String> filters) throws Exception {
        var request=get("/api/workforce/forecast");filters.forEach(request::param);
        var result=mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }
    @Test void anonymousHappyPathUsesActualFilteredSourcesAndRequiredPageShape() throws Exception {
        when(mapper.needs()).thenReturn(List.of(new Demand(3,"BE","S","2026-12",1,2,new BigDecimal("0.50"),new BigDecimal("55")),new Demand(4,"FE","J","2026-12",1,9,BigDecimal.ONE,BigDecimal.TEN)));
        var data=getData(Map.of("from","2026-12","months","1","job","BE","grade","S"));
        var page=json.treeToValue(data,WorkforcePage.class);
        assertThat(page.total()).isEqualTo(1);assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).demand_mm()).isEqualByComparingTo("0.55");
        assertThat(page.items().get(0).supply_mm()).isEqualByComparingTo("1");
        assertThat(page.items().get(0).remaining_mm()).isEqualByComparingTo("0.45");
        assertThat(page.target_utilization_rate()).isEqualTo(90);
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("items","page","size","total","shortages","months","target_utilization_rate","message");
    }
    @ParameterizedTest @ValueSource(strings={"from=2026-13","from=bad","months=0","months=7","year=0","year=10000","page=-1","size=21","job=bad!","grade=bad!","sort=salary,asc","sort=month,up","sort=weighted_total,asc","unknown=x"})
    void invalidInputHasFieldMessageAndCorrelationWithoutSourceReads(String input) throws Exception {
        reset(mapper,profit);var parts=input.split("=",2);
        mvc.perform(get("/api/workforce/forecast").param(parts[0],parts[1]))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(parts[0]))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper,profit);
    }
    @Test void inactiveFilterCodeRejectedWithoutBusinessSourceRead() throws Exception {
        when(mapper.code(any())).thenReturn(false);
        mvc.perform(get("/api/workforce/forecast").param("job","UNKNOWN")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("job"));
        verify(mapper,never()).developers(any());
    }
    @Test void repeatedReadsMatchSharedCandidateAndOpportunityForecastValues() throws Exception {
        var filters=Map.of("from","2026-12","months","1","job","BE","grade","S");
        var first=getData(filters);var second=getData(filters);
        WorkforcePlanningAssertions.assertForecastItemsEqual(first,second);
        WorkforcePlanningAssertions.assertForecastItemsEqual(first,json.readTree(json.writeValueAsBytes(service.opportunityForecast(filters).workforce())));
        assertThat(first.get("items").get(0).get("shortage_mm").decimalValue()).isEqualByComparingTo(service.candidateMonths(filters).get(0).shortage_mm());
    }
    @Test void emptySourceReturnsNoRowsAndZeroTotal() throws Exception {
        when(mapper.developers(any())).thenReturn(List.of());
        var data=getData(Map.of("from","2026-12","months","1"));
        assertThat(data.get("items")).isEmpty();assertThat(data.get("total").asLong()).isZero();
    }
    @Test void seoulDefaultsAndYearIntersectionExcludePastMonths() throws Exception {
        assertThat(getData(Map.of()).get("months").get(0).asText()).isEqualTo("2026-12");
        assertThat(getData(Map.of("year","2026")).get("months")).hasSize(1);
        var past=getData(Map.of("year","2025"));assertThat(past.get("months")).isEmpty();
        assertThat(past.get("message").asText()).isEqualTo("예측할 미래월이 없습니다.");
    }
    @Test void zeroDenominatorAndMissingCostAndBaselineRemainExplicit() throws Exception {
        when(mapper.developers(any())).thenReturn(List.of(new Developer(1,"BE",null,"FT",null,LocalDate.of(2026,12,1),null)));
        var row=getData(Map.of("from","2026-12","months","1")).get("items").get(0);
        assertThat(row.get("utilization_rate").isNull()).isTrue();assertThat(row.get("baseline_operating_profit").isNull()).isTrue();
        assertThat(row.get("average_labor_cost").asLong()).isZero();assertThat(row.get("warnings").toString()).contains("원가 미확인","등급 미분류","FUTURE_ACTUAL");
    }
    @Test void overAssignedPersonHasZeroSupplyAndWarningWithoutCappingActual() throws Exception {
        when(mapper.assignments()).thenReturn(List.of(assignment(1,"S")));
        when(mapper.assignmentMonths()).thenReturn(List.of(new AssignedMonth(1,"2026-12",new BigDecimal("1.25"),null)));
        var row=getData(Map.of("from","2026-12","months","1")).get("items").get(0);
        assertThat(row.get("supply_mm").decimalValue()).isZero();assertThat(row.get("confirmed_mm").decimalValue()).isEqualByComparingTo("1.25");
        assertThat(row.get("warnings").toString()).contains("MM 1.0 초과");
    }
    private Assignment assignment(long id,String grade){return new Assignment(id,5,1,"BE",grade,LocalDate.of(2026,12,1),LocalDate.of(2026,12,31),null);}
    @Test void multipleSourcePlansSubtractConfirmedOnlyOnceAndNeverAcrossGrades() throws Exception {
        when(mapper.plans()).thenReturn(List.of(new ProjectPlan(5,"2026-12","BE","S",BigDecimal.ONE),new ProjectPlan(5,"2026-12","BE","S",BigDecimal.ONE),new ProjectPlan(5,"2026-12","BE","J",BigDecimal.ONE)));
        when(mapper.assignments()).thenReturn(List.of(assignment(1,"S")));
        when(mapper.assignmentMonths()).thenReturn(List.of(new AssignedMonth(1,"2026-12",BigDecimal.ONE,null)));
        var rows=service.forecast(WorkforceDemandInput.parse(Map.of("from","2026-12","months","1"),clock)).items();
        assertThat(rows).filteredOn(r->r.grd_cd().equals("S")).singleElement().satisfies(r->assertThat(r.demand_mm()).isEqualByComparingTo("1"));
        assertThat(rows).filteredOn(r->r.grd_cd().equals("J")).singleElement().satisfies(r->assertThat(r.demand_mm()).isEqualByComparingTo("1"));
    }
    @Test void filteredSortedPagesKeepWholeWindowShortages() throws Exception {
        var needs=new ArrayList<Demand>();for(int i=0;i<25;i++)needs.add(new Demand(i,"JOB"+i,"S","2026-12",1,1,new BigDecimal("0.41"),new BigDecimal("100")));
        when(mapper.needs()).thenReturn(needs);when(mapper.developers(any())).thenReturn(List.of());
        var data=getData(Map.of("from","2026-12","months","1","sort","job_cd,desc","page","1","size","20"));
        assertThat(data.get("items")).hasSize(5);assertThat(data.get("total").asInt()).isEqualTo(25);assertThat(data.get("shortages")).hasSize(25);
        var sorted=needs.stream().map(Demand::job).sorted(Comparator.reverseOrder()).toList();
        assertThat(data.get("items").get(0).get("job_cd").asText()).isEqualTo(sorted.get(20));
    }
    @ParameterizedTest @ValueSource(strings={"0.40","0.41"})
    void shortageThresholdUsesUnroundedStrictComparison(String quantity) throws Exception {
        when(mapper.developers(any())).thenReturn(List.of());when(mapper.needs()).thenReturn(List.of(new Demand(1,"BE","S","2026-12",1,1,new BigDecimal(quantity),new BigDecimal("100"))));
        var data=getData(Map.of("from","2026-12","months","1"));assertThat(data.get("shortages")).hasSize(quantity.equals("0.40")?0:1);
        if(quantity.equals("0.41")){assertThat(data.get("shortages").get(0).get("recommended_hiring_month").asText()).isEqualTo("2026-10");assertThat(data.get("shortages").get(0).get("review_immediately").asBoolean()).isTrue();}
    }
    @Test void inclusiveOverlappingLeaveUnionAndHireResignBoundariesDetermineAvailableDays() throws Exception {
        when(mapper.developers(any())).thenReturn(List.of(new Developer(1,"BE","S","FT",LocalDate.of(2026,12,5),LocalDate.of(2026,12,25),null)));
        when(mapper.leaves()).thenReturn(List.of(new Leave(1,LocalDate.of(2026,12,6),LocalDate.of(2026,12,10)),new Leave(1,LocalDate.of(2026,12,8),LocalDate.of(2026,12,15))));
        var row=getData(Map.of("from","2026-12","months","1")).get("items").get(0);
        assertThat(row.get("available_mm").decimalValue()).isEqualByComparingTo(BigDecimal.TEN.divide(BigDecimal.valueOf(31),12,RoundingMode.HALF_UP));
    }
    @Test void extensionRepeatsAndStopsInNextConfirmedStartMonthWithTempSeparate() throws Exception {
        when(mapper.assignments()).thenReturn(List.of(new Assignment(1,5,1,"BE","S",LocalDate.of(2026,10,1),LocalDate.of(2026,11,30),"H"),new Assignment(2,6,1,"BE","S",LocalDate.of(2027,2,15),LocalDate.of(2027,3,31),null)));
        when(mapper.assignmentMonths()).thenReturn(List.of(new AssignedMonth(1,"2026-11",new BigDecimal("0.50"),null),new AssignedMonth(2,"2027-02",new BigDecimal("0.50"),null)));
        when(mapper.temporary()).thenReturn(List.of(new Temporary("BE","S","2026-12","2027-02",new BigDecimal("0.75"))));
        var rows=getData(Map.of("from","2026-12","months","3")).get("items");
        assertThat(rows.get(0).get("extension_mm").decimalValue()).isEqualByComparingTo("0.40");
        assertThat(rows.get(1).get("extension_mm").decimalValue()).isEqualByComparingTo("0.40");
        assertThat(rows.get(0).get("supply_mm").decimalValue()).isEqualByComparingTo("0.60");
        assertThat(rows.get(0).get("temporary_mm").decimalValue()).isEqualByComparingTo("0.75");
        assertThat(rows.get(2).get("extension_mm").decimalValue()).isZero();
    }
    @Test void idleCostUsesEachRealUnusedFtCtCapacityRatherThanDemandAllocationOrExternalCosts() throws Exception {
        when(mapper.developers(any())).thenReturn(List.of(new Developer(1,"BE","S","FT",null,null,10000000L),new Developer(2,"BE","S","CT",null,null,2000000L),new Developer(3,"BE","S","OS",null,null,90000000L)));
        when(mapper.assignments()).thenReturn(List.of(assignment(1,"S")));
        when(mapper.assignmentMonths()).thenReturn(List.of(new AssignedMonth(1,"2026-12",new BigDecimal("0.75"),null)));
        when(mapper.needs()).thenReturn(List.of(new Demand(1,"BE","S","2026-12",1,10,BigDecimal.ONE,new BigDecimal("100"))));
        var row=getData(Map.of("from","2026-12","months","1")).get("items").get(0);
        assertThat(row.get("idle_cost").asLong()).isEqualTo(4500000);
        assertThat(row.get("average_labor_cost").asLong()).isEqualTo(6000000);
        assertThat(row.get("baseline_operating_profit").isNull()).isTrue();
    }
    @Test void readOnlyCallUsesOnlyReadStatementsAndKeepsFixtureRecordsUnchanged() throws Exception {
        var sources=List.of(new Developer(1,"BE","S","FT",null,null,10000000L));when(mapper.developers(any())).thenReturn(sources);
        String before=json.writeValueAsString(sources);getData(Map.of("from","2026-12","months","1"));
        assertThat(json.writeValueAsString(sources)).isEqualTo(before);
        assertThat(mockingDetails(mapper).getInvocations()).allSatisfy(call->assertThat(call.getMethod().getName()).isIn("code","developers","leaves","assignments","assignmentMonths","plans","needs","temporary","candidates"));
    }
}
