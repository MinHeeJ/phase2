package com.sicost.apiops;

import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.us4.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.us4.PlanComparisonModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PlanComparisonController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PlanComparisonService.class,
    PlanComparisonExportService.class, ExcelExportService.class, CurrentUser.class,
    PermissionService.class, PlanComparisonReadApiTest.Time.class})
class PlanComparisonReadApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
    @Autowired MockMvc mvc;
    @MockBean PlanComparisonMapper mapper;

    @BeforeEach void setup() {
        when(mapper.tolerance("2027-01")).thenReturn(new BigDecimal("0.10"));
        when(mapper.projects(any())).thenReturn(List.of(new ProjectOption(42, "P-2642", "인력 계획")));
        when(mapper.annual(any())).thenReturn(List.of(
            source(42, "BE", "1998.00", "2.00"),
            source(43, "QA", "0.00", "1.00")));
    }
    private Source source(long id, String job, String plan, String actual) {
        return new Source(id, "P-" + id, "인력 계획", job, job, 1,
            new BigDecimal(plan), new BigDecimal(actual));
    }
    @Test void sourcePlanCapacityIsNotClippedToManualLimit() throws Exception {
        mvc.perform(get("/api/plan-vs-actual").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta.today").value("2027-01-01"))
            .andExpect(jsonPath("$.data.rows[0].planMm").value(1998))
            .andExpect(jsonPath("$.data.rows[0].actualMm").value(2))
            .andExpect(jsonPath("$.data.rows[0].gapMm").value(-1996))
            .andExpect(jsonPath("$.data.total").value(2));
        verify(mapper).tolerance("2027-01");
        verifyNoMoreInteractionsAfterRead();
    }
    @Test void filteredTotalAndTotalsRemainAvailableOnEmptyLaterPage() throws Exception {
        mvc.perform(get("/api/plan-vs-actual").param("year", "2026")
                .param("gap", "SHORT").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows").isEmpty())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.totals.planMm").value(1998))
            .andExpect(jsonPath("$.data.totals.actualMm").value(2));
    }
    @Test void projectAndJobScopeReachSameReadQuery() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(source(42, "BE", "2", "1")));
        mvc.perform(get("/api/plan-vs-actual").param("year", "2026")
                .param("projectId", "42").param("job", "BE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        verify(mapper).annual(argThat(q -> Long.valueOf(42).equals(q.projectId())
            && "BE".equals(q.job()) && "2026-01".equals(q.fromMonth())
            && "2026-12".equals(q.toMonth())));
    }
    @ParameterizedTest
    @CsvSource({"year,10000", "projectId,0", "job,a b", "gap,UNKNOWN", "sort,prj_id;drop", "page,-1", "size,100"})
    void invalidConditionStopsBeforePersistence(String field, String value) throws Exception {
        var request = get("/api/plan-vs-actual");
        if (!field.equals("year")) request.param("year", "2026");
        mvc.perform(request.param(field, value)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value(field));
        verifyNoInteractions(mapper);
    }
    @Test void missingToleranceDoesNotQueryBusinessRows() throws Exception {
        when(mapper.tolerance(anyString())).thenReturn(null);
        mvc.perform(get("/api/plan-vs-actual").param("year", "2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        verify(mapper).tolerance("2027-01");
        verifyNoMoreInteractions(mapper);
    }
    private void verifyNoMoreInteractionsAfterRead() {
        verify(mapper).annual(any());
        verify(mapper).projects(any());
        verifyNoMoreInteractions(mapper);
    }
}
