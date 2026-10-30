package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.costallocation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CostAllocationController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, CostAllocationService.class,
    CurrentUser.class, PermissionService.class})
class CostAllocationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean CostAllocationMapper mapper;
    @MockBean Clock clock;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-29T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        when(mapper.projectExists(101)).thenReturn(true);
        when(mapper.people("2026-10")).thenReturn(List.of(new Person(7, "FT", "DIRECT", 100L)));
        when(mapper.assignments("2026-10")).thenReturn(List.of(
            CostAllocationRulesTest.assignment(1, "1.00", "MO", "RUN", "NEW"),
            CostAllocationRulesTest.assignment(2, "0.50", "MO", "RUN", "NEW")));
    }

    @Test void anonymousReadUsesRealServiceEnvelopeActualIdentityAndSourceMm() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.closed").value(false))
            .andExpect(jsonPath("$.data.calculation_month").value("2026-10"))
            .andExpect(jsonPath("$.data.reference_month").value("2026-10"))
            .andExpect(jsonPath("$.data.unit").value("원"))
            .andExpect(jsonPath("$.data.items[0].dev_id").value(7))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(67))
            .andExpect(jsonPath("$.data.items[1].lbr_cst_amt").value(33))
            .andExpect(jsonPath("$.data.items[1].mm_qty").value(0.5));
        var order = inOrder(mapper);
        order.verify(mapper).monthStatus("2026-10");
        order.verify(mapper).people("2026-10");
        verify(mapper, never()).snapshots(anyString());
    }

    @Test void projectFilterIsAppliedAfterAllProjectDenominator() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "101"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(67))
            .andExpect(jsonPath("$.data.items[0].tot_mm_qty").value(1.5));
    }

    @Test void edgeSixOpenReadRecalculatesChangedMm() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(67));
        when(mapper.assignments("2026-10")).thenReturn(List.of(CostAllocationRulesTest.assignment(1, "0.40", "MO", "RUN", "NEW")));
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(40))
            .andExpect(jsonPath("$.data.items[1].aloc_ty_cd").value("IDLE"))
            .andExpect(jsonPath("$.data.items[1].lbr_cst_amt").value(60));
    }

    static AllocationRow snapshot() {
        return new AllocationRow("2026-09", 7L, 1L, 101L, "PROJECT", new BigDecimal("0.40"),
            new BigDecimal("0.40"), new BigDecimal("0.40"), 12, 3, 9, null, "N", "N", "N");
    }

    @Test void edgeSixClosedReadNeverTouchesMutableSourcesOrReclassifiesSnapshot() throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.snapshots("2026-09")).thenReturn(List.of(snapshot()));
        mvc.perform(get("/api/costs/allocation").param("month", "2026-09"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.closed").value(true))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(12))
            .andExpect(jsonPath("$.data.items[0].drct_expns_amt").value(3));
        verify(mapper, never()).people(anyString());
        verify(mapper, never()).assignments(anyString());
        verify(mapper, never()).expenses(anyString());
        verify(mapper, never()).turnkey(anyString());
    }

    @Test void closedMissingSnapshotIsAnErrorNotLiveFallback() throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.expectedCost(any())).thenReturn(true);
        mvc.perform(get("/api/costs/allocation").param("month", "2026-09"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"))
            .andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("마감 배분자료 없음")));
        verify(mapper, never()).people(anyString());
        verify(mapper, never()).assignments(anyString());
    }

    @Test void closedProjectWithoutSnapshotDoesNotUseAnotherProject() throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.snapshots("2026-09")).thenReturn(List.of(snapshot()));
        when(mapper.projectExists(102)).thenReturn(true);
        when(mapper.expectedCost(any())).thenReturn(true);
        mvc.perform(get("/api/costs/allocation").param("month", "2026-09").param("projectId", "102"))
            .andExpect(status().isConflict());
        verify(mapper, never()).assignments(anyString());
    }

    @Test void closedPeriodWithNoExpectedCostMayBeEmptyWithoutReadingLiveAmounts() throws Exception {
        when(mapper.monthStatus("2026-08")).thenReturn("CLOSED");
        mvc.perform(get("/api/costs/allocation").param("month", "2026-08"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.closed").value(true))
            .andExpect(jsonPath("$.data.items").isEmpty());
        verify(mapper).expectedCost(new Query("2026-08", null));
        verify(mapper, never()).people(anyString());
        verify(mapper, never()).assignments(anyString());
    }

    @Test void emptyOpenPeriodReturnsRealEmptyList() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-08"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.unallocated_cost_amt").value(0));
    }

    @Test void missingMonthUsesValidationEnvelope() throws Exception {
        mvc.perform(get("/api/costs/allocation")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest @ValueSource(strings = {"", "2026-1", "2026-13", "2026-11", "2027-01", "x", "0000-01"})
    void malformedOrFutureMonthIs400(String month) throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", month)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "1.0", "9223372036854775808", "1;select"})
    void malformedOrNonpositiveIdentifierIs400(String project) throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", project))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("projectId"));
        verifyNoInteractions(mapper);
    }

    @Test void absentProjectIs404() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "999"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper, never()).monthStatus(anyString());
    }

    @Test void emptyOptionalFilterAddsNoProjectCondition() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", ""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        verify(mapper, never()).projectExists(anyLong());
    }
}
