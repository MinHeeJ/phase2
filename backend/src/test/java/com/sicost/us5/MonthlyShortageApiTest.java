package com.sicost.us5;

import static com.sicost.us5.MonthlyShortageModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = MonthlyShortageController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, MonthlyShortageService.class,
    MonthlyShortageExportService.class, ExcelExportService.class, CurrentUser.class,
    PermissionService.class, MonthlyShortageApiTest.Time.class})
class MonthlyShortageApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean MonthlyShortageMapper mapper;
    @BeforeEach void setup() {
        when(mapper.tolerance(anyString())).thenReturn(new BigDecimal("0.10"));
        when(mapper.months(any())).thenReturn(List.of(row(1,"2026-03","3.00","2.50")));
    }
    Row row(long project, String month, String plan, String actual) {
        var p = new BigDecimal(plan); var a = new BigDecimal(actual);
        return new Row(project,"프로젝트 " + project,"BE","백엔드 개발",month,p,a,p.subtract(a));
    }
    @Test void anonymousSuccessfulMonthlyRowHasApprovedWireAndNoSalary() throws Exception {
        mvc.perform(get("/api/plan-vs-actual/shortages").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].plan_ym").value("2026-03"))
            .andExpect(jsonPath("$.data.rows[0].planMm").value(3.0))
            .andExpect(jsonPath("$.data.rows[0].actualMm").value(2.5))
            .andExpect(jsonPath("$.data.rows[0].shortageMm").value(0.5))
            .andExpect(jsonPath("$.data.rows[0].pay_untprc_amt").doesNotExist());
        verify(mapper).tolerance("2026-10");
    }
    @Test void matchToleranceAndOverMonthsAreNotShortages() throws Exception {
        when(mapper.months(any())).thenReturn(List.of(row(1,"2026-01","3","3"),
            row(1,"2026-02","3","2.90"),row(1,"2026-03","3","4")));
        mvc.perform(get("/api/plan-vs-actual/shortages")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows").isEmpty()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void annualOffsetDoesNotHideMarchShortage() throws Exception {
        when(mapper.months(any())).thenReturn(List.of(row(1,"2026-03","3","2.50"),row(1,"2026-04","3","3.50")));
        mvc.perform(get("/api/plan-vs-actual/shortages")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].plan_ym").value("2026-03"));
    }
    @Test void effectiveDbToleranceIsNotHardcoded() throws Exception {
        when(mapper.tolerance(anyString())).thenReturn(new BigDecimal("0.50"));
        mvc.perform(get("/api/plan-vs-actual/shortages")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void missingToleranceFailsExplicitly() throws Exception {
        when(mapper.tolerance(anyString())).thenReturn(null);
        mvc.perform(get("/api/plan-vs-actual/shortages")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }
    @Test void sharesYearProjectAndJobFilters() throws Exception {
        mvc.perform(get("/api/plan-vs-actual/shortages").param("year","2025").param("projectId","7").param("job","BE"))
            .andExpect(status().isOk());
        verify(mapper).months(argThat(q -> q.year()==2025 && q.projectId()==7 && q.job().equals("BE")
            && q.firstMonth().equals("2025-01") && q.lastMonth().equals("2025-12")));
    }
    @ParameterizedTest @ValueSource(strings={"year=0","year=10000","year=no","projectId=0","projectId=x","job=a!","page=-1","size=20","gap=SHORT","sort=sql,asc"})
    void invalidListQueryHasSingularField(String input) throws Exception {
        String[] pair=input.split("=");
        mvc.perform(get("/api/plan-vs-actual/shortages").param(pair[0],pair[1]))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(pair[0]));
        verify(mapper,never()).months(any());
    }
    @Test void sortingAndTotalsPrecedePaginationWithStableProjectTies() throws Exception {
        when(mapper.months(any())).thenReturn(IntStream.rangeClosed(1,55).mapToObj(i -> row(56-i,"2026-03","3","2.50")).toList());
        mvc.perform(get("/api/plan-vs-actual/shortages").param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(55))
            .andExpect(jsonPath("$.data.rows.length()").value(5)).andExpect(jsonPath("$.data.rows[0].prj_id").value(51));
    }
    @Test void shortageDescendingThenMonthAscending() throws Exception {
        when(mapper.months(any())).thenReturn(List.of(row(1,"2026-04","3","2.50"),
            row(1,"2026-03","3","2.50"),row(1,"2026-12","3","1")));
        mvc.perform(get("/api/plan-vs-actual/shortages")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows[0].plan_ym").value("2026-12"))
            .andExpect(jsonPath("$.data.rows[1].plan_ym").value("2026-03"));
    }
    @Test void exportContainsAllRowsNumericMmTwoDecimalsAndServerFilename() throws Exception {
        when(mapper.months(any())).thenReturn(IntStream.rangeClosed(1,55).mapToObj(i -> row(i,"2026-03","3","2.50")).toList());
        var response = mvc.perform(get("/api/plan-vs-actual/shortages/export").param("year","2026").param("job","BE"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("2026_20261014.xlsx");
        try(var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet=workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(58);
            assertThat(sheet.getRow(3).getCell(5).getStringCellValue()).isEqualTo("부족");
            assertThat(sheet.getRow(4).getCell(5).getNumericCellValue()).isEqualTo(0.5);
            assertThat(sheet.getRow(4).getCell(5).getCellStyle().getDataFormatString()).isEqualTo("0.00");
        }
    }
    @ParameterizedTest @ValueSource(strings={"gap=SHORT","page=1","size=50","year=no","projectId=0","job=!","sort=bad"})
    void invalidExportQueryHasSingularField(String input) throws Exception {
        String[] pair=input.split("=");
        mvc.perform(get("/api/plan-vs-actual/shortages/export").param(pair[0],pair[1]))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(pair[0]));
    }
    @Test void emptyExportStillHasDisplayedHeaders() throws Exception {
        when(mapper.months(any())).thenReturn(List.of());
        var response=mvc.perform(get("/api/plan-vs-actual/shortages/export")).andExpect(status().isOk()).andReturn().getResponse();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(3);
        }
    }
}
