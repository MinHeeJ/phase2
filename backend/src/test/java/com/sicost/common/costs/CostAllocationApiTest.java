package com.sicost.common.costs;

import com.sicost.apiops.*;
import com.sicost.common.api.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.common.costs.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CostAllocationController.class)
@Import({ProjectCostService.class, ApiExceptionHandler.class, RequestIdFilter.class})
class CostAllocationApiTest {
    @Autowired MockMvc mvc;
    @MockBean ProjectBoardMapper mapper;
    @BeforeEach void setup() {
        when(mapper.allocationProject(11)).thenReturn(11L);
        when(mapper.allocationProject(12)).thenReturn(12L);
        when(mapper.allocationSalaries(anyString())).thenReturn(List.of(new Salary(7, "FT", "DIRECT", 100L)));
        when(mapper.allocationAssignments(anyString())).thenReturn(List.of(
            new Assignment(1, 7, 11, new BigDecimal("1.00"), "MO", "RUN", "NEW", 100L),
            new Assignment(2, 7, 12, new BigDecimal("0.50"), "MO", "RUN", "NEW", 100L)));
    }
    @Test void publicReadCalculatesAllDenominatorsBeforeFilter() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "11"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(67))
            .andExpect(jsonPath("$.data.items[0].tot_mm_qty").value(1.5));
        verify(mapper).allocationAssignments("2026-10");
    }
    @Test void noFilterReadsBothProjects() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
    }
    @Test void emptySourcesAreSuccessfulEmptyResult() throws Exception {
        when(mapper.allocationSalaries(anyString())).thenReturn(List.of());
        when(mapper.allocationAssignments(anyString())).thenReturn(List.of());
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"", "2026-00", "2026-13", "0000-01", "26-10", "2026-1", "2026-10 ", "2026-10-01"})
    void malformedMonthIsValidationError(String month) throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", month))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }
    @Test void requiredMonthIsValidationError() throws Exception {
        mvc.perform(get("/api/costs/allocation")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @ParameterizedTest @ValueSource(strings = {"", "0", "-1", "1.0", "abc", "9223372036854775808"})
    void invalidStorageKeyIsValidationError(String id) throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", id))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("projectId"))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(mapper);
    }
    @Test void missingProjectReturnsNotFound() throws Exception {
        when(mapper.allocationProject(99)).thenReturn(null);
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "99"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void closedMissingSnapshotNeverFallsBackToLiveSources() throws Exception {
        when(mapper.allocationMonthStatus("2026-10")).thenReturn("CLOSED");
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("마감 배분자료 없음")));
        verify(mapper, never()).allocationSalaries(anyString());
        verify(mapper, never()).allocationAssignments(anyString());
        verify(mapper, never()).allocationExpenses(anyString());
    }
    @Test void closedSnapshotKeepsStoredClassificationsAndAmounts() throws Exception {
        when(mapper.allocationMonthStatus("2026-10")).thenReturn("CLOSED");
        when(mapper.allocationSnapshots("2026-10")).thenReturn(List.of(new Allocation(7L, 11L, 1L, "RND",
            new BigDecimal("0.40"), new BigDecimal("0.40"), new BigDecimal("0.4"), 17, 9, 3L, null, false, false, true)));
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.closed").value(true))
            .andExpect(jsonPath("$.data.items[0].aloc_ty_cd").value("RND"))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(17));
        verify(mapper, never()).allocationAssignments(anyString());
    }
    @Test void partialSnapshotMissingRequestedProjectIsExplicit() throws Exception {
        when(mapper.allocationMonthStatus("2026-10")).thenReturn("CLOSED");
        when(mapper.allocationSnapshots("2026-10")).thenReturn(List.of(new Allocation(7L, 12L, 2L, "PROJECT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 100, 0, 0L, null, false, false, false)));
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "11"))
            .andExpect(status().isConflict());
    }
    @Test void openSourceChangesRecalculateWhileMmRemainsSourceMm() {
        var owner = new ProjectCostService(mapper);
        assertThat(owner.allocation("2026-10", 11L).items().get(0).lbr_cst_amt()).isEqualTo(67);
        when(mapper.allocationSalaries("2026-10")).thenReturn(List.of(new Salary(7, "FT", "DIRECT", 200L)));
        assertThat(owner.allocation("2026-10", 11L).items().get(0).lbr_cst_amt()).isEqualTo(133);
        assertThat(owner.allocation("2026-10", 11L).items().get(0).mm_qty()).isEqualByComparingTo("1.00");
    }
    @Test void explicitAnnualBoundsDoNotChangeAllTimeLegacyCostMethod() {
        when(mapper.costs(any())).thenCallRealMethod();
        when(mapper.allocationMonths(any())).thenReturn(List.of("2025-12", "2026-01", "2026-10"));
        var owner = new ProjectCostService(mapper);
        assertThat(owner.calculate(11, "2026-10").cumulative_cost_amt()).isEqualTo(201);
        assertThat(owner.calculateAnnual(11, YearMonth.of(2026, 10)).cumulative_cost_amt()).isEqualTo(134);
    }
}
