package com.sicost.workforce;

import static com.sicost.workforce.WorkforceForecastModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WorkforceController.class)
@Import({WorkforceForecastService.class,PermissionService.class,ApiExceptionHandler.class,RequestIdFilter.class,WorkforceForecastApiTest.Time.class})
class WorkforceForecastApiTest {
    @TestConfiguration static class Time {@Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"),ZoneOffset.UTC);}}
    @Autowired MockMvc mvc;
    @MockBean WorkforceForecastMapper mapper;
    static BigDecimal n(String s){return new BigDecimal(s);}
    Source row(String month,String supply,String demand){return new Source(month,"BE","SR",n(supply),n(demand),n("1"),n("0.3"),n("0"),n("0.4"),n("700"),n("1000"),false,false);}
    @BeforeEach void setup(){when(mapper.utilizationTarget(anyString())).thenReturn(n("90"));}
    @Test void anonymousForecastUsesNextSeoulMonthAndExcludesTemporaryFromSupply()throws Exception {
        when(mapper.forecast(any())).thenReturn(List.of(row("2026-12","0.7","1.1")));
        mvc.perform(get("/api/workforce/forecast")).andExpect(status().isOk()).andExpect(jsonPath("$.data.months[0]").value("2026-12"))
            .andExpect(jsonPath("$.data.months[5]").value("2027-05"))
            .andExpect(jsonPath("$.data.items[0].supply_mm").value(0.70)).andExpect(jsonPath("$.data.items[0].temporary_mm").value(0.40))
            .andExpect(jsonPath("$.data.items[0].shortage_mm").value(0.40)).andExpect(jsonPath("$.data.shortages").isEmpty());
        verify(mapper).forecast(argThat(r->r.today().equals(LocalDate.of(2026,11,1))&&r.from().equals("2026-12")&&r.to().equals("2027-05")));
        verify(mapper,never()).candidateCount(any());
    }
    @Test void strictShortageBoundaryAndHiringLeadAreSharedByCandidateApi()throws Exception {
        when(mapper.forecast(any())).thenReturn(List.of(row("2027-03","0.69","1.10")));when(mapper.candidateCount(any())).thenReturn(3L);
        mvc.perform(get("/api/candidates/shortages")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].maximum_shortage_mm").value(0.41)).andExpect(jsonPath("$.data.items[0].maximum_shortage_people").value(1))
            .andExpect(jsonPath("$.data.items[0].recommended_hiring_month").value("2027-01")).andExpect(jsonPath("$.data.items[0].candidate_count").value(3));
        verify(mapper).candidateCount(new CandidateMatch("BE","SR",LocalDate.of(2027,3,31)));
    }
    @Test void pastYearHasExplicitEmptyMessageAndNoSourceQuery()throws Exception {
        mvc.perform(get("/api/workforce/forecast").param("year","2025")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.months").isEmpty())
            .andExpect(jsonPath("$.data.message").value("예측할 미래월이 없습니다."));verify(mapper,never()).forecast(any());
    }
    @Test void zeroCapacityHasNullUtilizationAndUnavailableProfitIsNotInvented()throws Exception {
        when(mapper.forecast(any())).thenReturn(List.of(new Source("2026-12","BE",null,n("0"),n("0"),n("0"),n("0"),n("0"),n("0"),n("0"),n("0"),false,true)));
        mvc.perform(get("/api/workforce/forecast")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].utilization_rate").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].baseline_operating_profit").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[0].warnings").isNotEmpty());
    }
    @ParameterizedTest @CsvSource({"from,2026-13","from,2026-11","months,7","months,0","size,30","page,-1","sort,unknown"})
    void invalidInputHasSafeFieldErrorAndNeverReadsSources(String key,String value)throws Exception {
        mvc.perform(get("/api/workforce/forecast").param(key,value)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value(key)).andExpect(jsonPath("$.requestId").isNotEmpty());verifyNoInteractions(mapper);
    }
    @Test void shortagesAndTotalsAreCalculatedBeforePagination()throws Exception {
        var rows=new ArrayList<Source>();for(int i=0;i<25;i++)rows.add(new Source("2026-12","JOB_"+i,null,n("0"),n("1"),n("0"),n("0"),n("0"),n("0"),n("0"),n("0"),false,true));
        when(mapper.forecast(any())).thenReturn(rows);
        mvc.perform(get("/api/workforce/forecast").param("size","20").param("page","1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(25)).andExpect(jsonPath("$.data.items.length()").value(5)).andExpect(jsonPath("$.data.shortages.length()").value(25));
    }
}
