package com.sicost.costsops;

import com.sicost.apiops.*;
import com.sicost.common.api.*;
import com.sicost.common.costs.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.common.costs.CostAllocationModels.*;
import static com.sicost.costsops.RenegotiationModels.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {RenegotiationController.class, CostAllocationController.class})
@Import({RenegotiationService.class, ProjectCostService.class, CostStandards.class,
    PermissionService.class, ApiExceptionHandler.class, RequestIdFilter.class, RenegotiationApiTest.Time.class})
class RenegotiationApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean RenegotiationMapper mapper;
    @MockBean ProjectBoardMapper board;
    static BigDecimal n(String s) { return new BigDecimal(s); }
    static Candidate candidate(long id) {
        return new Candidate(id, 7, "개발자", "FT", 11, "진행 프로젝트", "MO", LocalDate.of(2026, 12, 31));
    }
    @BeforeEach void setup() {
        when(board.params("2026-10")).thenReturn(List.of(new ProjectBoardModels.OperatingParam(
            "TARGET_MARGIN_RATE", "2000-01", "목표 이익률", n("20"), null, "%", null)));
        when(board.allocationProject(11)).thenReturn(11L);
        when(board.allocationSalaries(anyString())).thenReturn(List.of(new Salary(7, "FT", "DIRECT", 90L)));
        when(board.allocationAssignments(anyString())).thenReturn(List.of(
            new Assignment(1, 7, 11, n("1.00"), "MO", "RUN", "NEW", 100L)));
        when(mapper.candidates(any())).thenReturn(List.of(candidate(1)));
    }
    @Test void anonymousSuccessAndDefaultsUseSeoulMonthAndRealAllocation() throws Exception {
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.items[0].margin_rt").value(10))
            .andExpect(jsonPath("$.data.items[0].target_untprc_amt").value(10000))
            .andExpect(jsonPath("$.data.items[0].required_raise_rt").value(12.5))
            .andExpect(jsonPath("$.data.summary.annual_increase_amt").value(60))
            .andExpect(jsonPath("$.data.summary.target_annual_increase_amt").value(150));
        verify(mapper).candidates(new CandidateQuery(LocalDate.of(2026,10,23), LocalDate.of(2027,4,23), "2026-10", null, null));
    }
    @ParameterizedTest @CsvSource({"windowMonths,4", "windowMonths,abc", "marginBelow,14", "marginBelow,NaN",
        "raiseRate,-1", "raiseRate,50.01", "raiseRate,abc", "empType,ANY", "projectId,0", "projectId,abc",
        "page,-1", "size,0", "size,201", "sort,end_dt;delete"})
    void eachInvalidQueryIsValidationError(String field, String value) throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param(field,value)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value(field));
        verifyNoInteractions(mapper, board);
    }
    @ParameterizedTest @CsvSource({"0,0", "50,600"})
    void raiseEndpointsAreReadOnly(String rate, int increase) throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param("raiseRate",rate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].annual_increase_amt").value(increase));
        verify(board).allocationAssignments("2026-10");
    }
    @Test void filtersAndCountUseSameCalculatedSetAndSummaryIsUnpaged() throws Exception {
        when(mapper.candidates(any())).thenReturn(List.of(candidate(1), candidate(2)));
        when(board.allocationAssignments(anyString())).thenReturn(List.of(
            new Assignment(1,7,11,n("0.50"),"MO","RUN","NEW",100L),
            new Assignment(2,7,11,n("0.50"),"MO","RUN","NEW",100L)));
        mvc.perform(get("/api/costs/renegotiations").param("empType","FT").param("projectId","11")
            .param("size","1").param("page","1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].asgn_id").value(2))
            .andExpect(jsonPath("$.data.summary.people_count").value(1))
            .andExpect(jsonPath("$.data.summary.annual_increase_amt").value(60));
        verify(mapper).candidates(new CandidateQuery(LocalDate.of(2026,10,23), LocalDate.of(2027,4,23), "2026-10", "FT", 11L));
    }
    @Test void equalityIsNotBelowAndEmptyIsSuccessful() throws Exception {
        when(board.allocationSalaries(anyString())).thenReturn(List.of(new Salary(7,"FT","DIRECT",85L)));
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.summary.weighted_margin_rt").isEmpty());
    }
    @Test void absentProjectIsNotFound() throws Exception {
        when(board.allocationProject(99)).thenReturn(null);
        mvc.perform(get("/api/costs/renegotiations").param("projectId","99"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void closedMissingMaterialsDoNotQueryLiveSources() throws Exception {
        when(board.allocationMonthStatus("2026-10")).thenReturn("CLOSED");
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("마감 배분자료 없음")));
        verify(board,never()).allocationAssignments(anyString());
    }
    @Test void closedReadKeepsFrozenPriceMmAndCostDespiteLiveRateChanges() throws Exception {
        when(board.allocationMonthStatus("2026-10")).thenReturn("CLOSED");
        when(board.allocationSnapshots("2026-10")).thenReturn(List.of(new Allocation(7L,11L,1L,"PROJECT",
            n("1.00"),n("1.00"),n("1"),90,0,100L,100L,false,false,false)));
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.closed").value(true)).andExpect(jsonPath("$.data.items[0].bill_untprc_amt").value(100));
        verify(board,never()).allocationAssignments(anyString());
    }
    @Test void missingSalaryIsVisibleNotZeroCostProfit() throws Exception {
        when(board.allocationSalaries(anyString())).thenReturn(List.of(new Salary(7,"FT","DIRECT",null)));
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].margin_rt").isEmpty())
            .andExpect(jsonPath("$.data.items[0].allocated_cost_amt").isEmpty())
            .andExpect(jsonPath("$.data.items[0].warnings").isNotEmpty());
    }
    @Test void allocationOperationRemainsOwnedByFoundation() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(90));
    }
    @Test void missingTargetStandardIsExplicitValidationFailure() throws Exception {
        when(board.params(anyString())).thenReturn(List.of());
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("month"));
    }
}
