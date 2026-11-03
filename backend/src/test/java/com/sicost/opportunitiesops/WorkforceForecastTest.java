package com.sicost.opportunitiesops;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.sicost.opportunitiesops.OpportunityForecastModels.*;
import com.sicost.common.finance.MonthlyProfitReader;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkforceForecastTest {
    OpportunityForecastMapper mapper;
    WorkforceForecastService service;
    Clock clock=Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"),ZoneId.of("Asia/Seoul"));
    @BeforeEach void setup(){mapper=mock(OpportunityForecastMapper.class);var profit=mock(MonthlyProfitReader.class);service=new WorkforceForecastService(mapper,profit,clock);when(mapper.code(any())).thenReturn(true);}
    Query q(String from,int months){return OpportunityForecastInput.parse(Map.of("from",from,"months",Integer.toString(months)),clock);}
    Developer developer(){return new Developer(1,"BE","S","FT",null,null,10000000L);}
    @Test void defaultsUseSeoulNextMonthAndYearIntersection(){var q=OpportunityForecastInput.parse(Map.of(),clock);assertThat(q.months()).containsExactly("2026-12","2027-01","2027-02","2027-03","2027-04","2027-05");assertThat(OpportunityForecastInput.parse(Map.of("year","2026"),clock).months()).containsExactly("2026-12");assertThat(OpportunityForecastInput.parse(Map.of("year","2025"),clock).months()).isEmpty();}
    @Test void overlappingLeaveDatesAreUnionNotDoubleSubtracted(){when(mapper.developers(any())).thenReturn(List.of(developer()));when(mapper.leaves()).thenReturn(List.of(new Leave(1,LocalDate.of(2026,12,1),LocalDate.of(2026,12,10)),new Leave(1,LocalDate.of(2026,12,5),LocalDate.of(2026,12,15))));var r=service.forecast(q("2026-12",1)).items().get(0);assertThat(r.available_mm()).isEqualByComparingTo(BigDecimal.valueOf(16).divide(BigDecimal.valueOf(31),12,RoundingMode.HALF_UP));assertThat(r.baseline_operating_profit()).isNull();assertThat(r.warnings()).anyMatch(w->w.contains("FUTURE_ACTUAL"));}
    @Test void resignationAndHireBoundaryAreInclusive(){when(mapper.developers(any())).thenReturn(List.of(new Developer(1,"BE","S","FT",LocalDate.of(2026,12,10),LocalDate.of(2026,12,20),null)));var r=service.forecast(q("2026-12",1)).items().get(0);assertThat(r.available_mm()).isEqualByComparingTo(BigDecimal.valueOf(10).divide(BigDecimal.valueOf(31),12,RoundingMode.HALF_UP));assertThat(r.average_labor_cost()).isZero();assertThat(r.warnings()).contains("원가 미확인");}
    @ParameterizedTest @CsvSource({"0.40,false","0.41,true"})void shortageUsesStrictUnroundedThreshold(String mm,boolean expected){when(mapper.needs()).thenReturn(List.of(new Demand(7,"BE","S","2026-12",1,1,new BigDecimal(mm),new BigDecimal("100"))));var r=service.forecast(q("2026-12",1));assertThat(r.shortages().size()).isEqualTo(expected?1:0);if(expected){assertThat(r.shortages().get(0).recommended_hiring_month()).isEqualTo("2026-10");assertThat(r.shortages().get(0).review_immediately()).isTrue();}}
    @Test void temporaryIsOverlayNotSupplySubtraction(){when(mapper.developers(any())).thenReturn(List.of(developer()));when(mapper.temporary()).thenReturn(List.of(new Temporary("BE","S","2026-12","2026-12",new BigDecimal("0.75"))));var r=service.forecast(q("2026-12",1)).items().get(0);assertThat(r.supply_mm()).isEqualByComparingTo("1");assertThat(r.temporary_mm()).isEqualByComparingTo("0.75");}
    @Test void demandUsesSavedProbabilityAndActualNeedPeriod(){when(mapper.needs()).thenReturn(List.of(new Demand(7,"BE","S","2026-12",1,2,BigDecimal.ONE,new BigDecimal("55"))));var r=service.forecast(q("2026-12",2));assertThat(r.items().get(0).demand_mm()).isEqualByComparingTo("1.10");assertThat(r.items().get(1).demand_mm()).isZero();}
    @Test void actualsAreAttributedToOneGradeAndOneProjectBucket(){when(mapper.plans()).thenReturn(List.of(new ProjectPlan(1,"2026-12","BE","S",new BigDecimal("2")),new ProjectPlan(1,"2026-12","BE","J",new BigDecimal("2"))));when(mapper.assignments()).thenReturn(List.of(new Assignment(4,1,1,"BE","S",LocalDate.of(2026,12,1),LocalDate.of(2026,12,31),null)));when(mapper.assignmentMonths()).thenReturn(List.of(new AssignedMonth(4,"2026-12",BigDecimal.ONE,100L)));var r=service.forecast(q("2026-12",1));assertThat(r.items()).filteredOn(m->"S".equals(m.grd_cd())).singleElement().satisfies(m->assertThat(m.demand_mm()).isEqualByComparingTo("1"));assertThat(r.items()).filteredOn(m->"J".equals(m.grd_cd())).singleElement().satisfies(m->assertThat(m.demand_mm()).isEqualByComparingTo("2"));}
    @Test void extensionRepeatsUntilNextConfirmedStarts(){when(mapper.developers(any())).thenReturn(List.of(developer()));when(mapper.assignments()).thenReturn(List.of(new Assignment(4,1,1,"BE","S",LocalDate.of(2026,10,1),LocalDate.of(2026,11,30),"H"),new Assignment(5,2,1,"BE","S",LocalDate.of(2027,1,15),LocalDate.of(2027,2,28),null)));when(mapper.assignmentMonths()).thenReturn(List.of(new AssignedMonth(4,"2026-11",BigDecimal.ONE,100L),new AssignedMonth(5,"2027-01",new BigDecimal("0.5"),100L)));var r=service.forecast(q("2026-12",2));assertThat(r.items().get(0).extension_mm()).isEqualByComparingTo("0.80");assertThat(r.items().get(0).supply_mm()).isEqualByComparingTo("0.20");assertThat(r.items().get(1).extension_mm()).isZero();assertThat(r.items().get(1).supply_mm()).isEqualByComparingTo("0.5");}
    @Test void revenueUsesSalesInputAndSaved55PercentNotQuote(){when(mapper.opportunities(any())).thenReturn(List.of(new Opportunity(7,1001,"2026-12",2,new BigDecimal("55"))));var r=service.opportunityForecast(Map.of("from","2026-12","months","2"));assertThat(r.items()).extracting(RevenueMonth::opportunity_revenue).containsExactly(275L,275L);assertThat(r.kpi().all_won_total()).isEqualTo(1001);assertThat(r.kpi().weighted_total()).isEqualTo(550);}
    @Test void turnkeyRemainderAssignedToLastMonthAndInternalExcluded(){when(mapper.projects()).thenReturn(List.of(new Project(1,"TK",LocalDate.of(2026,12,1),LocalDate.of(2027,2,28),1001L),new Project(2,"IN",LocalDate.of(2026,12,1),LocalDate.of(2027,2,28),1000L)));var r=service.opportunityForecast(Map.of("from","2026-12","months","3"));assertThat(r.items()).extracting(RevenueMonth::project_revenue).containsExactly(333L,333L,335L);}
    @Test void monthlyRevenueUsesEffectiveRateAndMmOnly(){when(mapper.projects()).thenReturn(List.of(new Project(1,"MO",LocalDate.of(2026,12,1),LocalDate.of(2027,2,28),1001L)));when(mapper.assignments()).thenReturn(List.of(new Assignment(4,1,1,"BE","S",LocalDate.of(2026,12,1),LocalDate.of(2026,12,31),null)));when(mapper.assignmentMonths()).thenReturn(List.of(new AssignedMonth(4,"2026-12",new BigDecimal("0.55"),1001L)));var r=service.opportunityForecast(Map.of("from","2026-12","months","1"));assertThat(r.items().get(0).project_revenue()).isEqualTo(550);}
    @Test void workforceAndCandidateProjectionShareSameValues(){when(mapper.needs()).thenReturn(List.of(new Demand(7,"BE","S","2026-12",1,1,BigDecimal.ONE,new BigDecimal("55"))));var filters=Map.of("from","2026-12","months","1");var raw=service.opportunityForecast(filters).workforce().items().get(0);assertThat(service.candidateMonths(filters).get(0).shortage_mm()).isEqualByComparingTo(raw.shortage_mm());verify(mapper,never()).candidates(argThat(f->!f.job().equals("BE")));}
    @Test void candidatesAvailableInLaterShortageMonthsAreIncludedOnce(){
        when(mapper.needs()).thenReturn(List.of(new Demand(7,"BE","S","2026-12",3,1,BigDecimal.ONE,new BigDecimal("100"))));
        when(mapper.candidates(new CandidateFilter("BE","S","2027-02"))).thenReturn(2L);
        var result=service.forecast(q("2026-12",3));
        assertThat(result.shortages()).singleElement().satisfies(s->{assertThat(s.first_shortage_month()).isEqualTo("2026-12");assertThat(s.candidate_count()).isEqualTo(2);});
        verify(mapper,times(1)).candidates(new CandidateFilter("BE","S","2027-02"));
    }
    @Test void invalidCodesFailInsteadOfPretendingEmptyData(){when(mapper.code(any())).thenReturn(false);assertThatThrownBy(()->service.opportunityForecast(Map.of("job","BAD"))).isInstanceOf(com.sicost.common.api.ApiException.class);}
}
