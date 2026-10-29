package com.sicost.common;

import com.sicost.common.api.*;
import com.sicost.common.costallocation.*;
import com.sicost.common.costallocation.CostAllocationModels.Scope;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.common.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CostAllocationController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, CostAllocationService.class,
    CostAllocationEngine.class, CostAllocationApiTest.Time.class})
class CostAllocationApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired CostAllocationService service;
    @MockBean CostAllocationMapper mapper;
    @MockBean PermissionService permissions;
    @BeforeEach void setup() {
        when(mapper.people("2026-10")).thenReturn(List.of(new Person(7, "FT", "DIRECT", 100L)));
        when(mapper.assignments("2026-10")).thenReturn(List.of(CostAllocationEngineTest.assignment(1, "1.00"), CostAllocationEngineTest.assignment(2, "0.50")));
        when(mapper.project(101)).thenReturn(101L);
    }
    @Test void anonymousQueryHasApprovedEnvelopeAndNoInventedPermissionMetadata() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.status").value("OPEN")).andExpect(jsonPath("$.data.referenceMonth").value("2026-11"))
            .andExpect(jsonPath("$.data.unit").value("KRW")).andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(67))
            .andExpect(jsonPath("$.data.items[1].lbr_cst_amt").value(33));
        verifyNoInteractions(permissions);
    }
    @Test void projectFilterIsAppliedAfterAllProjectsFormTheDenominator() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "101"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].tot_mm_qty").value(1.5))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(67));
    }
    @Test void closedSnapshotIsReturnedWithoutReadingOpenSources() throws Exception {
        reset(mapper);
        var frozen = new Allocation("2026-10", 7, 101L, 1L, "PROJECT", new BigDecimal("0.40"),
            new BigDecimal("0.40"), new BigDecimal("0.400000"), 987L, 123, 456L, 1000L, "N", "N", "N");
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        when(mapper.snapshots(new Scope("2026-10", null))).thenReturn(List.of(frozen));
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(987));
        verify(mapper, never()).people(anyString()); verify(mapper, never()).assignments(anyString());
        verify(mapper, never()).expenses(anyString());
    }
    @Test void missingClosedSnapshotIsConflictNeverRecalculated() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        when(mapper.missingSnapshots(any())).thenReturn(1L);
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"))
            .andExpect(jsonPath("$.message").value("마감 배분자료 없음"))
            .andExpect(jsonPath("$.field").value("month")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper, never()).people(anyString()); verify(mapper, never()).snapshots(any());
    }
    @Test void missingMonthIsFeatureValidationNotLegacyInvalidInput() throws Exception {
        mvc.perform(get("/api/costs/allocation")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper);
        assertThat(ApiException.badRequest("기존", "month").code()).isEqualTo("INVALID_INPUT");
    }
    @ParameterizedTest @ValueSource(strings = {"", "2026-1", "2026-13", "2026-00", "2026-10-01", " 2026-10"})
    void malformedMonthIsValidation(String value) throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", value)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "1.0", "9223372036854775808", "01", ""})
    void malformedProjectIdIsValidation(String value) throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("projectId"));
        verifyNoInteractions(mapper);
    }
    @Test void unknownProjectIsNotFound() throws Exception {
        when(mapper.project(999)).thenReturn(null);
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10").param("projectId", "999"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper, never()).people(anyString());
    }
    @Test void emptyOpenMonthStillReturnsNonemptyResultObject() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-11"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.warnings").isEmpty()).andExpect(jsonPath("$.data.status").value("OPEN"));
    }
    @Test void projectAdapterCountsProjectExpenseOnceAndSharesMissingSalaryEvidence() {
        when(mapper.projectMonths(any())).thenReturn(List.of("2026-10"));
        when(mapper.expenses("2026-10")).thenReturn(List.of(new Expense(1, null, 101L, "MO", "RUN", 9)));
        assertThat(service.projectTotal(101, "2026-10").amount()).isEqualTo(76);
        when(mapper.people("2026-10")).thenReturn(List.of(new Person(7, "FT", "DIRECT", null)));
        var result = service.projectTotal(101, "2026-10");
        assertThat(result.amount()).isEqualTo(9); assertThat(result.missing_salary_months()).containsExactly("2026-10");
    }
    @Test void pagingReusesExistingZeroBasedDefaultsAndTranslatesOnlyLocalErrors() {
        var paging = CostAnalysisInput.paging(Map.of());
        assertThat(paging.page()).isZero(); assertThat(paging.size()).isEqualTo(50); assertThat(paging.offset()).isZero();
        for (int size : List.of(20, 50, 100)) assertThat(CostAnalysisInput.paging(Map.of("page", "2", "size", "" + size)).offset()).isEqualTo(2L * size);
    }
    @ParameterizedTest @CsvSource({"page,-1", "page,2147483647", "page,abc", "size,30", "size,-1"})
    void pagingErrorsUseSingularField(String field, String value) {
        assertThatThrownBy(() -> CostAnalysisInput.paging(Map.of(field, value))).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo("VALIDATION_ERROR"); assertThat(ex.field()).isEqualTo(field);
        });
    }
}
