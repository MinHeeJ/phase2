package com.sicost.businessplan;

import com.sicost.common.security.PermissionService;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.costallocation.CostAllocationModels.*;
import com.sicost.people.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.sicost.people.PeopleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessPlanUtilizationTest {
    PeopleMapper mapper;
    CostAllocationService allocation;
    PeopleCostService service;
    @BeforeEach void setup() {
        mapper=mock(PeopleMapper.class);allocation=mock(CostAllocationService.class);
        service=new PeopleCostService(mapper,allocation,Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"),ZoneId.of("Asia/Seoul")),new PermissionService());
        when(mapper.projects(any())).thenReturn(List.of());
        when(allocation.calculate(anyString(),isNull())).thenAnswer(i->new Allocation(i.getArgument(0),"2026-02",false,"원",List.of(),List.of(),0));
    }
    PersonMonth person(long id,String month,Long part) {
        return new PersonMonth(id,"인력",null,null,null,"FT","DIRECT",part,"DEV",null,month,false,true,0,0,0,0,0,0,0,0,0,0);
    }
    @Test void monthlyAdapterUsesMonthScopedPopulationInsteadOfRepeatedYearToDate() {
        when(mapper.people(new PeopleModels.Period("2026-01","2026-01"))).thenReturn(List.of(person(1,"2026-01",7L)));
        when(mapper.people(new PeopleModels.Period("2026-02","2026-02"))).thenReturn(List.of(person(1,"2026-02",7L),person(2,"2026-02",7L)));
        for(String month:List.of("2026-01","2026-02"))when(allocation.calculate(month,null)).thenReturn(new Allocation(month,"2026-02",false,"원",List.of(),
            List.of(new AllocationRow(month,1L,9L,7L,"PROJECT",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,0,0,0,null,"N","N","N")),0));
        assertThat(service.monthlyUtilization("2026-01",7L).utilization_rt()).isEqualByComparingTo("100.0");
        assertThat(service.monthlyUtilization("2026-02",7L).utilization_rt()).isEqualByComparingTo("50.0");
        verify(allocation,never()).year(anyInt(),any());
    }
    @Test void emptyDirectDenominatorRemainsNull() {
        when(mapper.people(any())).thenReturn(List.of());
        assertThat(service.monthlyUtilization("2026-02",null).utilization_rt()).isNull();
    }
    @Test void partFilterUsesSamePopulationForDenominatorAndNumerator() {
        when(mapper.people(any())).thenReturn(List.of(person(1,"2026-02",7L),person(2,"2026-02",8L)));
        assertThat(service.monthlyUtilization("2026-02",7L).psn_cnt()).isEqualTo(1);
        assertThat(service.monthlyUtilization("2026-02",7L).utilization_rt()).isEqualByComparingTo("0.0");
    }
}
