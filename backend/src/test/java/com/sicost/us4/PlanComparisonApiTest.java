package com.sicost.us4;

import static com.sicost.us4.PlanComparisonModels.*;
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
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PlanComparisonController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PlanComparisonService.class,
    PlanComparisonExportService.class, ExcelExportService.class, CurrentUser.class,
    PermissionService.class, PlanComparisonApiTest.Time.class})
class PlanComparisonApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean PlanComparisonMapper mapper;
    @BeforeEach void setup() {
        when(mapper.tolerance("2026-10")).thenReturn(new BigDecimal("0.10"));
        when(mapper.annual(any())).thenReturn(List.of(source(1, "BE", "3", "2.50")));
        when(mapper.projects(any())).thenReturn(List.of(new ProjectOption(1,"P-2601","연간 비교")));
    }
    Source source(long id, String job, String plan, String actual) {
        return new Source(id,"P-"+id,"연간 비교",job,job,1,new BigDecimal(plan),new BigDecimal(actual));
    }
    @Test void anonymousAnnualReturnsEnvelopeAndServerToday() throws Exception {
        mvc.perform(get("/api/plan-vs-actual").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta.today").value("2026-10-14"))
            .andExpect(jsonPath("$.meta.planGapTolerance").value(.10))
            .andExpect(jsonPath("$.meta.projects[0].prj_id").value(1))
            .andExpect(jsonPath("$.data.rows[0].gap").value("SHORT"))
            .andExpect(jsonPath("$.data.rows[0].gapMm").value(-.50))
            .andExpect(jsonPath("$.data.rows[0].salary").doesNotExist())
            .andExpect(jsonPath("$.data.totals.planMm").value(3));
    }
    @ParameterizedTest
    @CsvSource({"2.50,SHORT","3.20,OVER","3.00,MATCH","2.95,MATCH","2.90,MATCH","3.10,MATCH","2.89,SHORT","3.11,OVER"})
    void acOneAndTwoToleranceBoundaries(String actual, String gap) throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(source(1,"BE","3",actual)));
        mvc.perform(get("/api/plan-vs-actual").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].gap").value(gap));
    }
    @Test void actualOnlyJobUsesZeroPlan() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(source(1,"QA","0","0.40")));
        mvc.perform(get("/api/plan-vs-actual").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].planMm").value(0))
            .andExpect(jsonPath("$.data.rows[0].gapMm").value(.4))
            .andExpect(jsonPath("$.data.rows[0].gap").value("OVER"));
    }
    @Test void filterPrecedesTotalAndPagination() throws Exception {
        var rows = new ArrayList<>(IntStream.rangeClosed(1,51).mapToObj(i -> source(i,"BE","3","2.50")).toList());
        rows.add(source(99,"QA","0","0.40"));
        when(mapper.annual(any())).thenReturn(rows);
        mvc.perform(get("/api/plan-vs-actual").param("year","2026").param("gap","SHORT").param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(1))
            .andExpect(jsonPath("$.data.rows[0].prj_id").value(51))
            .andExpect(jsonPath("$.data.total").value(51))
            .andExpect(jsonPath("$.data.totals.planMm").value(153))
            .andExpect(jsonPath("$.data.totals.actualMm").value(127.5));
    }
    @Test void optionalFiltersArePassedWithYearBounds() throws Exception {
        mvc.perform(get("/api/plan-vs-actual").param("year","2026").param("projectId","1").param("job","BE"))
            .andExpect(status().isOk());
        verify(mapper).annual(argThat(q -> q.projectId() == 1 && q.job().equals("BE")
            && q.fromMonth().equals("2026-01") && q.toMonth().equals("2026-12")
            && q.start().equals(LocalDate.of(2026,1,1)) && q.end().equals(LocalDate.of(2026,12,31))));
    }
    @Test void deterministicTiesUseProjectIdentityAndJobCode() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(source(2,"QA","3","3"), source(1,"QA","3","3"), source(1,"BE","3","3")));
        mvc.perform(get("/api/plan-vs-actual").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].prj_id").value(1))
            .andExpect(jsonPath("$.data.rows[0].job_cd").value("BE"))
            .andExpect(jsonPath("$.data.rows[2].prj_id").value(2));
    }
    @Test void explicitDescendingSortIsApplied() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(source(1,"BE","3","2"),source(2,"QA","3","4")));
        mvc.perform(get("/api/plan-vs-actual").param("year","2026").param("sort","actualMm,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].prj_id").value(2));
    }
    @Test void emptyResultHasZeroTotals() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of());
        mvc.perform(get("/api/plan-vs-actual").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows").isEmpty())
            .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.totals.actualMm").value(0));
    }
    @ParameterizedTest @CsvSource({"year,zero","year,0","year,10000","projectId,-1","projectId,abc","job,a b","gap,unknown","sort,sql;drop","sort,gapMm,","page,-1","size,20","unknown,true"})
    void invalidQueryHasSingularField(String field, String value) throws Exception {
        var request = get("/api/plan-vs-actual").param("year","2026");
        if (field.equals("year")) request = get("/api/plan-vs-actual");
        mvc.perform(request.param(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));
    }
    @Test void yearIsRequired() throws Exception {
        mvc.perform(get("/api/plan-vs-actual")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
    }
    @Test void missingStandardDoesNotInventTolerance() throws Exception {
        when(mapper.tolerance(anyString())).thenReturn(null);
        mvc.perform(get("/api/plan-vs-actual").param("year","2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }
    @Test void negativeStandardIsRejected() throws Exception {
        when(mapper.tolerance(anyString())).thenReturn(new BigDecimal("-0.1"));
        mvc.perform(get("/api/plan-vs-actual").param("year","2026")).andExpect(status().isBadRequest());
    }
    @Test void exportContainsAllFilteredRowsTotalsAndDecimalCells() throws Exception {
        when(mapper.annual(any())).thenReturn(IntStream.rangeClosed(1,51).mapToObj(i -> source(i,"BE","3","2.50")).toList());
        var result = mvc.perform(get("/api/plan-vs-actual/export").param("year","2026").param("gap","SHORT"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("2026_20261014.xlsx"))).andReturn();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(55);
            assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("2026년 계획 MM");
            assertThat(sheet.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(2.5);
            assertThat(sheet.getRow(4).getCell(3).getCellStyle().getDataFormatString()).isEqualTo("0.00");
            assertThat(sheet.getRow(55).getCell(0).getStringCellValue()).isEqualTo("합계");
            assertThat(sheet.getRow(55).getCell(2).getNumericCellValue()).isEqualTo(153);
            assertThat(sheet.getRow(55).getCell(3).getNumericCellValue()).isEqualTo(127.5);
        }
    }
    @Test void exportRejectsPaginationRatherThanExportingOnePage() throws Exception {
        mvc.perform(get("/api/plan-vs-actual/export").param("year","2026").param("page","1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("page"));
    }
    @Test void exportKeepsProjectNamesAsTextNotFormulas() throws Exception {
        when(mapper.annual(any())).thenReturn(List.of(new Source(1,"P-1","=HYPERLINK(\"bad\")","BE","BE",1,BigDecimal.ONE,BigDecimal.ZERO)));
        var result = mvc.perform(get("/api/plan-vs-actual/export").param("year","2026")).andExpect(status().isOk()).andReturn();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
        }
    }
}
