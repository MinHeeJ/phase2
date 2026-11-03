package com.sicost.shortages;

import static com.sicost.us5.MonthlyShortageModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.us5.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
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
    PermissionService.class, ShortageApiTest.Time.class})
class ShortageApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean MonthlyShortageMapper mapper;
    @BeforeEach void setup() {
        when(mapper.tolerance(anyString())).thenReturn(new BigDecimal("0.10"));
        when(mapper.months(any())).thenReturn(List.of(row(1, "2027-03", "0.50")));
    }
    Row row(long id, String month, String shortage) {
        var difference = new BigDecimal(shortage);
        return new Row(id, "프로젝트 " + id, "BE", "백엔드 개발", month,
            BigDecimal.ONE, BigDecimal.ONE.subtract(difference), difference);
    }
    @Test void anonymousDefaultYearAndEffectiveToleranceUseSeoulNewYear() throws Exception {
        mvc.perform(get("/api/plan-vs-actual/shortages"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.total").value(1));
        verify(mapper).months(argThat(q -> q.year() == 2027));
        verify(mapper).tolerance("2027-01");
    }
    @Test void shortageThresholdUsesUnroundedDecimalAndExcludesSurplus() throws Exception {
        when(mapper.months(any())).thenReturn(List.of(row(1,"2027-01","0.1001"),
            row(2,"2027-02","0.10"), row(3,"2027-03","-0.50")));
        mvc.perform(get("/api/plan-vs-actual/shortages"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].shortageMm").value(0.1001));
    }
    @ParameterizedTest @ValueSource(strings = {"/api/plan-vs-actual/shortages", "/api/plan-vs-actual/shortages/export"})
    void invalidEffectiveToleranceStopsBeforeSourceRead(String path) throws Exception {
        when(mapper.tolerance(anyString())).thenReturn(new BigDecimal("-0.01"));
        mvc.perform(get(path)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.requestId").isString());
        verify(mapper, never()).months(any());
    }
    @Test void pageBeyondLastRetainsFilteredTotalWithoutIntegerOverflow() throws Exception {
        when(mapper.months(any())).thenReturn(IntStream.rangeClosed(1, 51)
            .mapToObj(i -> row(i,"2027-03","0.50")).toList());
        mvc.perform(get("/api/plan-vs-actual/shortages").param("projectId","73").param("job","BE")
            .param("page", Integer.toString(Integer.MAX_VALUE)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows").isEmpty())
            .andExpect(jsonPath("$.data.total").value(51));
        verify(mapper).months(argThat(q -> q.projectId() == 73L && "BE".equals(q.job())));
    }
    @Test void exportIsRealXlsxIncludesAllMatchingRowsAndTreatsFormulaNamesAsText() throws Exception {
        var rows = new ArrayList<Row>();
        rows.add(new Row(1,"=HYPERLINK(\"https://invalid.example\")","BE","+SUM(1,2)","2027-03",
            BigDecimal.ONE, new BigDecimal("0.50"), new BigDecimal("0.50")));
        IntStream.rangeClosed(2, 51).forEach(i -> rows.add(row(i,"2027-03","0.50")));
        when(mapper.months(any())).thenReturn(rows);
        var response = mvc.perform(get("/api/plan-vs-actual/shortages/export")
            .param("year","2027").param("projectId","73").param("job","BE"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("2027_20270101.xlsx");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(54);
            assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(sheet.getRow(4).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(4).getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(4).getCell(5).getCellStyle().getDataFormatString()).isEqualTo("0.00");
        }
        verify(mapper).months(argThat(q -> q.year() == 2027 && q.projectId() == 73L && "BE".equals(q.job())));
    }
    @ParameterizedTest @ValueSource(strings = {"/api/plan-vs-actual/shortages", "/api/plan-vs-actual/shortages/export"})
    void overflowingIdentifierIsValidationErrorNotInternalFailure(String path) throws Exception {
        mvc.perform(get(path).param("projectId","9223372036854775808"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("projectId"));
        verifyNoInteractions(mapper);
    }
}
