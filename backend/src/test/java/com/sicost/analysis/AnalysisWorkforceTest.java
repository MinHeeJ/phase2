package com.sicost.analysis;

import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.opportunitiesops.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.sicost.opportunitiesops.OpportunityForecastModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalysisWorkforceTest {
    OpportunityForecastMapper mapper;
    WorkforceForecastService service;
    Clock clock=Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"),ZoneId.of("Asia/Seoul"));
    @BeforeEach void setup(){mapper=mock(OpportunityForecastMapper.class);service=new WorkforceForecastService(mapper,mock(MonthlyProfitReader.class),clock);when(mapper.code(any())).thenReturn(true);}
    Query query(){return new Query(List.of("2026-12"),null,null,"month,asc",0,50,LocalDate.of(2026,11,1));}
    @Test void safeReadNeverLoadsPayrollAndPreservesSupplyDemandFormula(){
        when(mapper.nonSensitiveDevelopers()).thenReturn(List.of(new Developer(1,"BE","S","FT",null,null,null)));
        when(mapper.temporary()).thenReturn(List.of(new Temporary("BE","S","2026-12","2026-12",new BigDecimal("0.75"))));
        when(mapper.needs()).thenReturn(List.of(new Demand(7,"BE","S","2026-12",1,2,BigDecimal.ONE,new BigDecimal("55"))));
        var r=service.readNonSensitive(query(),Map.of(),Map.of()).items().get(0);
        assertThat(r.supply_mm()).isEqualByComparingTo("1");assertThat(r.demand_mm()).isEqualByComparingTo("1.10");assertThat(r.confirmed_mm()).isZero();assertThat(r.temporary_mm()).isEqualByComparingTo("0.75");verify(mapper,never()).developers(any());
    }
    @Test void workforceWhatIfOnlyChangesIndependentCalculationAndNotSources(){
        var demand=new Demand(7,"BE","S","2026-12",1,2,BigDecimal.ONE,new BigDecimal("55"));when(mapper.needs()).thenReturn(List.of(demand));
        assertThat(service.readNonSensitive(query(),Map.of(7L,new BigDecimal("100")),Map.of()).items().get(0).demand_mm()).isEqualByComparingTo("2");
        assertThat(service.readNonSensitive(query(),Map.of(),Map.of()).items().get(0).demand_mm()).isEqualByComparingTo("1.10");assertThat(demand.probability()).isEqualByComparingTo("55");
    }
    @Test void safeRevenueUsesOriginalRemainderAndProbabilityWithoutWorkforcePayroll(){
        when(mapper.projects()).thenReturn(List.of(new Project(1,"TK",LocalDate.of(2026,12,1),LocalDate.of(2027,2,28),1001L)));
        when(mapper.opportunities(any())).thenReturn(List.of(new Opportunity(7,1001,"2026-12",2,new BigDecimal("55"))));
        var result=service.readRevenue(query()).get(0);assertThat(result.project_revenue()).isEqualTo(333);assertThat(result.opportunity_revenue()).isEqualTo(275);assertThat(result.all_won_total()).isEqualTo(833);verify(mapper,never()).developers(any());verify(mapper,never()).nonSensitiveDevelopers();
    }
}
