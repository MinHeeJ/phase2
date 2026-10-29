package com.sicost.people;

import com.sicost.apiops.*;
import com.sicost.common.api.*;
import com.sicost.common.costs.CostAllocationModels.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.people.PeopleCostModels.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=PeopleCostController.class)
@Import({PeopleCostService.class,PermissionService.class,ApiExceptionHandler.class,RequestIdFilter.class,PeopleCostApiTest.Time.class})
class PeopleCostApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-02-15T00:00:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean PeopleCostMapper mapper;
    @MockBean ProjectCostService costs;
    static BigDecimal n(String v) { return new BigDecimal(v); }
    static PersonMonth person(long id,String month,String emp,boolean leave) {
        return new PersonMonth(id,month,"개발자"+id,"자사",emp,"DIRECT",1L,null,"BE","HIGH","팀원",leave,
            100L,0L,0L,0L,0L,0L,100L);
    }
    static Allocation allocation(long id,long assignment,String type,String mm,Long revenue,long cost) {
        return new Allocation(id,assignment,assignment,type,n(mm),n(mm),BigDecimal.ONE,cost,0L,revenue,
            revenue,false,false,false);
    }
    @BeforeEach void setup() {
        when(mapper.population(any())).thenReturn(List.of(person(1,"2026-01","FT",false),person(1,"2026-02","FT",false),
            person(2,"2026-01","FT",true),person(2,"2026-02","FT",true)));
        when(mapper.assignments(any())).thenReturn(List.of(new AssignmentInfo(11,11,"운영","MO","BE",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)),
            new AssignmentInfo(22,22,"구축","TK","BE",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31))));
        when(mapper.person(1)).thenReturn(1L);
        when(costs.allocation(anyString(),isNull())).thenAnswer(invocation->new Result(invocation.getArgument(0),false,
            List.of(allocation(1,11,"PROJECT","1.00",200L,100),allocation(2,22,"PROJECT","0.00",0L,100)),List.of(),List.of(),List.of()));
    }
    @Test void anonymousAnnualAndSummaryUseAllMonthsAndLeaveDenominator() throws Exception {
        mvc.perform(get("/api/costs/people").param("year","2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].cost").value(200))
            .andExpect(jsonPath("$.data.summary.utilization").value(50.0)).andExpect(jsonPath("$.data.summary.earning_rate").value(50.0))
            .andExpect(jsonPath("$.data.summary.average_cost").value(200)).andExpect(jsonPath("$.data.summary.average_profit").value(200));
        verify(costs,times(2)).allocation(anyString(),isNull());
    }
    @Test void averageUsesFullPeriodNotOnlySalaryMonths() throws Exception {
        when(mapper.population(any())).thenReturn(List.of(person(1,"2026-01","FT",false)));
        mvc.perform(get("/api/costs/people").param("year","2026").param("view","AVERAGE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].salary").value(50))
            .andExpect(jsonPath("$.data.items[0].mm").value(0.5));
    }
    @Test void lossFilterCountSummaryAndPaginationUseSameRows() throws Exception {
        mvc.perform(get("/api/costs/people").param("year","2026").param("lossOnly","true").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].dev_id").value(2)).andExpect(jsonPath("$.data.summary.people").value(1));
    }
    @Test void excludeTurnkeyKeepsNegativeCostButOmitsAveragePrice() throws Exception {
        mvc.perform(get("/api/costs/people").param("year","2026").param("contractType","TK").param("excludeTurnkeyPrice","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].profit").value(-200))
            .andExpect(jsonPath("$.data.summary.average_price").isEmpty());
    }
    @Test void overlapFilter() throws Exception {
        mvc.perform(get("/api/costs/people").param("year","2026").param("overlapOnly","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void populationFiltersAreConjunctive() throws Exception {
        mvc.perform(get("/api/costs/people").param("year","2026").param("partId","2").param("job","BE").param("empType","FT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void zeroRevenueMarginIsUnavailable() throws Exception {
        mvc.perform(get("/api/costs/people").param("year","2026").param("lossOnly","true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].margin").isEmpty());
    }
    @Test void breakdownRetainsSelectedPersonMonthlyEvidence() throws Exception {
        mvc.perform(get("/api/costs/people/1/breakdown").param("year","2026").param("view","AVERAGE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].dev_id").value(1)).andExpect(jsonPath("$.data.items[0].month").value("2026-01"))
            .andExpect(jsonPath("$.data.items[0].project").value("운영"));
    }
    @Test void missingPersonIs404() throws Exception {
        when(mapper.person(9)).thenReturn(null);
        mvc.perform(get("/api/costs/people/9/breakdown").param("year","2026")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","9223372036854775808"})
    void invalidPersonStorageKey(String id) throws Exception {
        mvc.perform(get("/api/costs/people/"+id+"/breakdown").param("year","2026")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("devId"));
    }
    @ParameterizedTest @ValueSource(strings={"year=0","year=2027","year=x","view=MONTH","empType=OTHER","partId=0","vendorId=no",
        "contractType=OTHER","lossOnly=yes","overlapOnly=1","excludeTurnkeyPrice=on","page=-1","size=101","sort=salary,asc"})
    void invalidQuery(String value) throws Exception {
        String[] pair=value.split("=");
        var request=get("/api/costs/people").param("year",pair[0].equals("year")?pair[1]:"2026");
        if(!pair[0].equals("year"))request.param(pair[0],pair[1]);
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value(pair[0]));
    }
    @Test void requiredYear() throws Exception {
        mvc.perform(get("/api/costs/people")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
    }
    @Test void missingClosedSnapshotIsNotReplaced() throws Exception {
        when(costs.allocation("2026-01",null)).thenThrow(ApiException.conflict("VALIDATION_ERROR","마감 배분자료 없음","month"));
        mvc.perform(get("/api/costs/people").param("year","2026")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("마감 배분자료 없음"));
    }
    @Test void missingSalaryWarningsSurviveAverage() throws Exception {
        when(costs.allocation("2026-01",null)).thenReturn(new Result("2026-01",false,List.of(),List.of(),
            List.of(new Warning(1L,null,"SALARY_MISSING","급여 미업로드")),List.of()));
        mvc.perform(get("/api/costs/people").param("year","2026").param("view","AVERAGE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].warnings[0]").value("2026-01 · 급여 미업로드"));
    }
}
