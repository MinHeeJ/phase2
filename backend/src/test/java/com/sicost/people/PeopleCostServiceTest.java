package com.sicost.people;

import com.sicost.apiops.ProjectCostService;
import com.sicost.common.costs.CostAllocationModels.*;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.sicost.people.PeopleCostModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PeopleCostServiceTest {
    final PeopleCostMapper mapper=mock(PeopleCostMapper.class);
    final ProjectCostService costs=mock(ProjectCostService.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-02-15T00:00:00Z"),ZoneOffset.UTC);
    final PeopleCostService service=new PeopleCostService(mapper,costs,clock,new PermissionService());
    @BeforeEach void setup() {
        when(mapper.assignments(any())).thenReturn(List.of(new AssignmentInfo(11,11,"운영","MO","BE",null,null)));
        when(costs.allocation(anyString(),isNull())).thenAnswer(i->new Result(i.getArgument(0),false,List.of(),List.of(),List.of(),List.of()));
    }
    Allocation allocation(long id,Long price,String mm) {
        return new Allocation(id,11L,11L,"PROJECT",new BigDecimal(mm),new BigDecimal(mm),BigDecimal.ONE,100,0,price,price,false,false,false);
    }
    @Test void monthlyRatesAreAveragesOfMonthlyRatiosNotRatioOfPeriodCounts() {
        when(mapper.population(any())).thenReturn(List.of(PeopleCostApiTest.person(1,"2026-01","FT",false),
            PeopleCostApiTest.person(1,"2026-02","FT",false),PeopleCostApiTest.person(2,"2026-02","FT",true)));
        for(String month:List.of("2026-01","2026-02")) when(costs.allocation(month,null)).thenReturn(new Result(month,false,
            List.of(allocation(1,0L,"1.00")),List.of(),List.of(),List.of()));
        var result=service.list(Map.of("year","2026"));
        assertThat(result.summary().utilization()).isEqualByComparingTo("75.0");
        assertThat(result.summary().earning_rate()).isEqualByComparingTo("75.0");
        assertThat(result.summary().average_price()).isEqualByComparingTo("0");
        assertThat(result.summary().average_cost()).isEqualByComparingTo("200");
        assertThat(result.contributions()).allSatisfy(c->assertThat(c.people()).isEqualTo(1));
    }
    @Test void noPopulationLeavesRatesAndAveragesUnavailableRatherThanHundredPercent() {
        var result=service.list(Map.of("year","2026"));
        assertThat(result.items()).isEmpty();
        assertThat(result.summary().average_cost()).isNull();
        assertThat(result.summary().average_price()).isNull();
        assertThat(result.summary().utilization()).isNull();
    }
    @Test void idleAndUnallocatedRemainSeparateEvidenceAndNegativeProfitUsesFloor() {
        when(mapper.person(1)).thenReturn(1L);
        when(mapper.population(any())).thenReturn(List.of(PeopleCostApiTest.person(1,"2026-01","FT",false),PeopleCostApiTest.person(1,"2026-02","FT",false)));
        when(costs.allocation("2026-01",null)).thenReturn(new Result("2026-01",false,List.of(
            new Allocation(1L,null,null,"IDLE",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,1,0,null,null,false,false,false)),
            List.of(),List.of(),List.of(new Unallocated(9,10,2))));
        var result=service.list(Map.of("year","2026","view","AVERAGE"));
        assertThat(result.items().get(0).profit()).isEqualByComparingTo("-1");
        var evidence=service.breakdown("1",Map.of("year","2026"));
        assertThat(evidence.items()).hasSize(1);
        assertThat(evidence.items().get(0).classification()).isEqualTo("IDLE");
        assertThat(evidence.items().get(0).profit()).isEqualTo(-1);
        when(mapper.person(9)).thenReturn(9L);
        var unallocated=service.breakdown("9",Map.of("year","2026"));
        assertThat(unallocated.items().get(0).classification()).isEqualTo("UNALLOCATED");
    }
    @Test void historicalYearIncludesDecemberAndSeoulCurrentYearStopsAtCurrentMonth() {
        service.list(Map.of("year","2025"));
        verify(costs).allocation("2025-12",null);
        verify(costs,never()).allocation("2026-01",null);
    }
    @Test void populationFilterNeverChangesAllocationDenominatorRequest() {
        service.list(Map.of("year","2026","empType","OS","vendorId","2","contractType","MO","job","BE"));
        verify(costs).allocation("2026-01",null);
        verify(costs).allocation("2026-02",null);
        verifyNoMoreInteractions(costs);
    }
}
