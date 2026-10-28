package com.sicost.us5;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.CurrentUser;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.us5.ShortageModels.*;

@WebMvcTest(controllers = ShortageController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ShortageService.class, CurrentUser.class, ExcelExportService.class, ShortageApiTest.Time.class})
class ShortageApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean ShortageRepository repository;
    @MockBean JdbcTemplate jdbc;
    private Row row() { return new Row(4, "프로젝트", "BE", "백엔드", "2026-03", new BigDecimal("3.00"), new BigDecimal("2.50"), new BigDecimal("0.50")); }
    @Test void listReturnsMonthlyRowsAndFilters() throws Exception {
        when(repository.find(any(), anyString())).thenReturn(List.of(row()));
        mvc.perform(get("/api/plan-vs-actual/shortages").param("year", "2026").param("projectId", "4").param("job", "BE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].month").value("2026-03"))
            .andExpect(jsonPath("$.data.rows[0].shortageMm").value(0.5));
        verify(repository).find(new Filter(2026, 4L, "BE", 1, 50), "2026-10");
    }
    @Test void emptyAndPagination() throws Exception {
        when(repository.find(any(), anyString())).thenReturn(List.of(row()));
        mvc.perform(get("/api/plan-vs-actual/shortages").param("page", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows").isEmpty());
    }
    @Test void invalidFilterIsRejected() throws Exception {
        mvc.perform(get("/api/plan-vs-actual/shortages").param("year", "wrong"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
        verifyNoInteractions(repository);
    }
    @Test void unexpectedFailureDoesNotLeakDetails() throws Exception {
        when(repository.find(any(), anyString())).thenThrow(new IllegalStateException("private SQL"));
        mvc.perform(get("/api/plan-vs-actual/shortages"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
    @Test void exportReturnsWorkbookAndRecordsAccess() throws Exception {
        when(repository.find(any(), anyString())).thenReturn(List.of(row()));
        mvc.perform(get("/api/plan-vs-actual/shortages/export").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(content().contentType(DownloadArtifact.XLSX));
        verify(jdbc).update(anyString(), any(), any(), any());
    }
}
