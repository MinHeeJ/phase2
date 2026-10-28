package com.sicost.us4;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.CurrentUser;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.us4.PlanComparisonModels.*;

@WebMvcTest(controllers=PlanComparisonController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PlanComparisonService.class, ExcelExportService.class, CurrentUser.class, PlanComparisonApiTest.Time.class})
class PlanComparisonApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean PlanComparisonMapper mapper;
    @BeforeEach void setup() {
        when(mapper.tolerance(anyString())).thenReturn(new BigDecimal("0.10"));
        when(mapper.annual(any())).thenReturn(List.of(
            new Source(1,"가","BE","개발",new BigDecimal("3.00"),new BigDecimal("2.89")),
            new Source(2,"나","FE","개발",new BigDecimal("3.00"),new BigDecimal("2.90")),
            new Source(3,"다","QA","품질",BigDecimal.ZERO,new BigDecimal("0.40"))));
    }
    @Test void anonymousListTotalsCoverAllPages() throws Exception {
        mvc.perform(get("/api/plan-vs-actual").param("year","2026").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.rows[0].gap").value("SHORT"))
            .andExpect(jsonPath("$.data.totals.planMm").value(6))
            .andExpect(jsonPath("$.data.totals.actualMm").value(6.19));
    }
    @Test void filtersBeforeTotals() throws Exception {
        mvc.perform(get("/api/plan-vs-actual").param("year","2026").param("gap","MATCH"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.totals.actualMm").value(2.9));
    }
    @Test void invalidFilterIsRejected() throws Exception {
        mvc.perform(get("/api/plan-vs-actual").param("year","2026").param("gap","OTHER")).andExpect(status().isBadRequest());
    }
    @Test void exportReturnsWorkbook() throws Exception {
        mvc.perform(get("/api/plan-vs-actual/export").param("year","2026"))
            .andExpect(status().isOk()).andExpect(content().contentType(DownloadArtifact.XLSX))
            .andExpect(header().exists("Content-Disposition"));
    }
}
