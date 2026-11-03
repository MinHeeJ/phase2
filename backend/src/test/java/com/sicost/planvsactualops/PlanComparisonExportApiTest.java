package com.sicost.planvsactualops;

import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.us4.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PlanComparisonController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PlanComparisonService.class,
    PlanComparisonExportService.class, ExcelExportService.class, CurrentUser.class,
    PermissionService.class, PlanComparisonExportApiTest.Time.class})
class PlanComparisonExportApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
    @Autowired MockMvc mvc;
    @MockBean PlanComparisonMapper mapper;

    @BeforeEach void setup() {
        when(mapper.tolerance("2026-10")).thenReturn(new BigDecimal("0.10"));
        when(mapper.projects(any())).thenReturn(List.of());
        when(mapper.annual(any())).thenReturn(List.of(source(1, "연간 프로젝트", "2.50")));
    }
    private Source source(long id, String name, String actual) {
        return new Source(id, "P-" + id, name, "BE", "백엔드 개발", 1,
            new BigDecimal("3.00"), new BigDecimal(actual));
    }
    private byte[] download(String... parameters) throws Exception {
        var request = get("/api/plan-vs-actual/export").param("year", "2026");
        for (int i = 0; i < parameters.length; i += 2) request.param(parameters[i], parameters[i + 1]);
        return mvc.perform(request).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("2026_20261014.xlsx")))
            .andReturn().getResponse().getContentAsByteArray();
    }
    @Test void anonymousDownloadIncludesEveryMatchingRowAndFilteredTotals() throws Exception {
        var sources = new ArrayList<>(IntStream.rangeClosed(1, 61)
            .mapToObj(i -> source(i, "연간 프로젝트", "2.50")).toList());
        sources.add(source(99, "초과 프로젝트", "4.00"));
        when(mapper.annual(any())).thenReturn(sources);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(download("gap", "SHORT", "projectId", "7", "job", "BE")))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(65);
            assertThat(IntStream.range(0, 5).mapToObj(i -> sheet.getRow(3).getCell(i).getStringCellValue()).toList())
                .containsExactly("프로젝트", "직무", "2026년 계획 MM", "실제·예정 MM", "차이");
            assertThat(sheet.getRow(64).getCell(3).getNumericCellValue()).isEqualTo(2.5);
            assertThat(sheet.getRow(65).getCell(2).getNumericCellValue()).isEqualTo(183);
            assertThat(sheet.getRow(65).getCell(3).getNumericCellValue()).isEqualTo(152.5);
            assertThat(sheet.getRow(4).getCell(2).getCellStyle().getDataFormatString()).isEqualTo("0.00");
            assertThat(sheet.getRow(4).getCell(4).getStringCellValue()).isEqualTo("부족 0.50");
        }
        verify(mapper).annual(argThat(q -> q.year() == 2026 && q.projectId() == 7L
            && q.job().equals("BE") && q.fromMonth().equals("2026-01") && q.toMonth().equals("2026-12")));
    }
    @ParameterizedTest @CsvSource({"3.10,맞음", "3.11,초과 0.11", "2.89,부족 0.11"})
    void downloadUsesTheSameToleranceClassificationAsTheScreen(String actual, String label) throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(source(1, "프로젝트", actual)));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(download()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(4).getStringCellValue()).isEqualTo(label);
        }
    }
    @Test void emptyDownloadIsAValidWorkbookWithHeadersAndZeroTotals() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(download()))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(4);
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("합계");
            assertThat(sheet.getRow(4).getCell(2).getNumericCellValue()).isZero();
            assertThat(sheet.getRow(4).getCell(3).getNumericCellValue()).isZero();
        }
    }
    @Test void exportHonorsExistingSortAndUsesStableIdentityTies() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(source(3, "같은 이름", "2"),
            source(2, "같은 이름", "4"), source(1, "같은 이름", "2")));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(download("sort", "actualMm,desc")))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(4);
            assertThat(sheet.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(2);
        }
        var rows = new PlanComparisonService(mapper, new Time().clock(), new PermissionService())
            .exportRows(PlanComparisonInput.query(Map.of("year", "2026", "sort", "actualMm,desc"), true));
        assertThat(rows.data().rows()).extracting(Row::prj_id).containsExactly(2L, 1L, 3L);
    }
    @Test void spreadsheetFormulaTextNeverBecomesExecutableFormula() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(source(1, "=HYPERLINK(\"https://invalid.example\")", "2.50")));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(download()))) {
            var cell = book.getSheetAt(0).getRow(4).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(book.getExternalLinksTable()).isEmpty();
        }
    }
    @ParameterizedTest @CsvSource({"year,0", "year,10000", "year,abc", "projectId,-1",
        "job,bad job", "gap,UNKNOWN", "sort,invalid", "page,1", "size,50", "unknown,value"})
    void invalidExportQueryReturnsSingularFieldAndDoesNotReadRows(String field, String value) throws Exception {
        var request = get("/api/plan-vs-actual/export");
        if (!field.equals("year")) request.param("year", "2026");
        mvc.perform(request.param(field, value)).andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.requestId").isString());
        verify(mapper, never()).annual(any());
    }
    @Test void absentYearIsRejected() throws Exception {
        mvc.perform(get("/api/plan-vs-actual/export")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("year"));
        verify(mapper, never()).annual(any());
    }
    @Test void missingToleranceFailsInsteadOfFabricatingAWorkbook() throws Exception {
        when(mapper.tolerance(anyString())).thenReturn(null);
        mvc.perform(get("/api/plan-vs-actual/export").param("year", "2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        verify(mapper, never()).annual(any());
    }
    @Test void databaseFailureReturnsJsonWithoutLeakingSql() throws Exception {
        when(mapper.annual(any())).thenThrow(new IllegalStateException("SELECT secret FROM private_table"));
        mvc.perform(get("/api/plan-vs-actual/export").param("year", "2026"))
            .andExpect(status().isInternalServerError()).andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.code").value("SERVER_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
            .andExpect(jsonPath("$.requestId").isString());
    }
}
