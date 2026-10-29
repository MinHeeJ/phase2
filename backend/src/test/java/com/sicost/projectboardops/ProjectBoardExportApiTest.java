package com.sicost.projectboardops;

import static com.sicost.projectboardops.ProjectBoardExportModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.projectcost.ProjectCostService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=ProjectBoardExportController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,ProjectBoardExportService.class,
    ExcelExportService.class,CurrentUser.class,PermissionService.class,ProjectBoardExportApiTest.Time.class})
class ProjectBoardExportApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneOffset.UTC); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectBoardExportMapper mapper;
    @MockBean ProjectCostService costs;
    @BeforeEach void setup() {
        when(mapper.parameters("2026-10")).thenReturn(params());
        when(mapper.sources(any())).thenReturn(List.of(source(1,"프로젝트",1,"20.0","42.5",null,null,0,0)));
        when(costs.calculate(anyLong(),any())).thenReturn(new ProjectCostService.ProjectCost(250000,List.of()));
    }
    List<BoardExportParam> params() {
        return List.of(new BoardExportParam("BUDGET_RATE",new BigDecimal("80"),null),
            new BoardExportParam("SIG_DELAY_WARN",new BigDecimal("5"),null),new BoardExportParam("SIG_DELAY_RISK",new BigDecimal("10"),null),
            new BoardExportParam("SIG_COST_WARN",new BigDecimal("5"),null),new BoardExportParam("SIG_COST_RISK",new BigDecimal("15"),null),
            new BoardExportParam("SIG_STALE_DAYS",new BigDecimal("14"),null));
    }
    BoardExportSource source(long id,String name,int phases,String plan,String actual,LocalDate memo,LocalDate start,long overdue,long high) {
        return new BoardExportSource(id,name,null,"TK","PM 미지정",null,phases,new BigDecimal(plan),new BigDecimal(actual),memo,start,
            1000000L,800000L,overdue,overdue,high);
    }
    XSSFWorkbook workbook(Map<String,String> filters) throws Exception {
        var request=get("/api/project-board/export"); filters.forEach(request::param);
        var response=mvc.perform(request).andExpect(status().isOk()).andExpect(content().contentType(DownloadArtifact.XLSX))
            .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION)).andReturn().getResponse();
        assertThat(org.springframework.http.ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
            .isEqualTo("프로젝트현황판_20261023.xlsx");
        return new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()));
    }
    @Test void anonymousBinaryWorkbookHasSeparateProgressAndNumericAmountsAndDownloadMetadata() throws Exception {
        try (var book=workbook(Map.of("type","TK","part","7"))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getRow(3).getCell(6).getStringCellValue()).isEqualTo("계획 진척률");
            assertThat(sheet.getRow(3).getCell(7).getStringCellValue()).isEqualTo("실적 진척률");
            assertThat(sheet.getRow(4).getCell(6).getNumericCellValue()).isEqualTo(20);
            assertThat(new DataFormatter().formatCellValue(sheet.getRow(4).getCell(7))).isEqualTo("42.5");
            assertThat(sheet.getRow(4).getCell(12).getNumericCellValue()).isEqualTo(250000);
            assertThat(sheet.getRow(4).getCell(13).getNumericCellValue()).isEqualTo(800000);
        }
        verify(mapper).sources(new BoardExportQuery(LocalDate.of(2026,10,23),"2026-10",null,"TK",7L));
        var capture=org.mockito.ArgumentCaptor.forClass(BoardExportAccess.class); verify(mapper).access(capture.capture());
        var detail=json.readTree(capture.getValue().detail());
        assertThat(detail.path("conditions").path("part").asLong()).isEqualTo(7);
        for (var column : detail.path("columns"))
            assertThat(column.path("sensitive").asBoolean()).isEqualTo(Set.of("cumulative_cost_amt","budget_amt").contains(column.path("key").asText()));
        assertThat(capture.getValue().actor()).isNull();
    }
    @Test void exportRoundsProgressAndWholeWonBudgetLikeTheBoard() throws Exception {
        var original=source(1,"프로젝트",1,"20.0","42.55",null,null,0,0);
        when(mapper.sources(any())).thenReturn(List.of(new BoardExportSource(original.prj_id(),original.prj_nm(),
            original.custName(),original.cntrct_ty_cd(),original.pmName(),original.currentPhase(),original.phaseCount(),
            original.plan_prgs_rt(),original.memoProgress(),original.last_memo_dt(),original.prfm_bgn_dt(),
            1000001L,800000L,original.open_issue_count(),original.overdue_issue_count(),original.highOverdueCount())));
        try (var book=workbook(Map.of())) {
            var row=book.getSheetAt(0).getRow(4);
            assertThat(row.getCell(7).getNumericCellValue()).isEqualTo(42.6);
            assertThat(row.getCell(13).getNumericCellValue()).isEqualTo(800000);
        }
    }
    @Test void fullSetIsSortedByLightNameAndIdWithoutFiftyRowLimit() throws Exception {
        when(costs.calculate(anyLong(),any())).thenReturn(new ProjectCostService.ProjectCost(0,List.of()));
        var rows=new ArrayList<BoardExportSource>();
        for (int n=105;n>=1;n--) rows.add(source(n,"프로젝트",1,"0","0",null,null,n==105?1:0,n==105?1:0));
        when(mapper.sources(any())).thenReturn(rows);
        try (var book=workbook(Map.of())) {
            var sheet=book.getSheetAt(0); assertThat(sheet.getLastRowNum()).isEqualTo(108);
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("위험");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("정상");
        }
    }
    @Test void computedLightFilterIsAppliedAfterCalculation() throws Exception {
        when(costs.calculate(anyLong(),any())).thenReturn(new ProjectCostService.ProjectCost(0,List.of()));
        when(mapper.sources(any())).thenReturn(List.of(source(1,"정상대상",1,"0","0",null,null,0,0),source(2,"위험대상",1,"50","0",null,null,0,0)));
        try (var book=workbook(Map.of("light","위험"))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(4);
            assertThat(book.getSheetAt(0).getRow(4).getCell(1).getStringCellValue()).isEqualTo("위험대상");
        }
    }
    @ParameterizedTest @ValueSource(strings={"light","type","part","page","size","sort","month"})
    void invalidAndUnsupportedFiltersReturn400WithNoDownloadHistory(String field) throws Exception {
        mvc.perform(get("/api/project-board/export").param(field,"invalid")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper,never()).sources(any()); verify(mapper,never()).access(any());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","9223372036854775808","1 OR 1=1",""})
    void invalidPartIs400(String part) throws Exception {
        mvc.perform(get("/api/project-board/export").param("part",part)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("part"));
        verify(mapper,never()).access(any());
    }
    @ParameterizedTest @ValueSource(strings={"BUDGET_RATE","SIG_DELAY_WARN","SIG_DELAY_RISK","SIG_COST_WARN","SIG_COST_RISK","SIG_STALE_DAYS"})
    void eachMissingNumericParameterReturnsMonthErrorBeforeSourceOrAudit(String key) throws Exception {
        when(mapper.parameters(anyString())).thenReturn(params().stream().filter(p->!p.key().equals(key)).toList());
        mvc.perform(get("/api/project-board/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(key)));
        verify(mapper,never()).sources(any()); verify(mapper,never()).access(any());
    }
    @Test void nonnumericParameterIsNotReplacedByDefault() throws Exception {
        var values=new ArrayList<>(params()); values.set(0,new BoardExportParam("BUDGET_RATE",null,"not-a-number"));
        when(mapper.parameters(anyString())).thenReturn(values);
        mvc.perform(get("/api/project-board/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
        verify(mapper,never()).access(any());
    }
    @Test void numericTextParameterUsesExistingValue() throws Exception {
        var values=new ArrayList<>(params()); values.set(0,new BoardExportParam("BUDGET_RATE",null,"75"));
        when(mapper.parameters(anyString())).thenReturn(values);
        try (var book=workbook(Map.of())) { assertThat(book.getSheetAt(0).getRow(4).getCell(13).getNumericCellValue()).isEqualTo(800000); }
    }
    @Test void storedTargetOverridesContractTimesBudgetRate() throws Exception {
        var p=source(1,"목표 원가",1,"0","0",null,null,0,0);
        when(mapper.sources(any())).thenReturn(List.of(new BoardExportSource(p.prj_id(),p.prj_nm(),p.custName(),p.cntrct_ty_cd(),
            p.pmName(),p.currentPhase(),p.phaseCount(),p.plan_prgs_rt(),p.memoProgress(),p.last_memo_dt(),p.prfm_bgn_dt(),
            p.cur_cntrct_amt(),625000L,p.open_issue_count(),p.overdue_issue_count(),p.highOverdueCount())));
        try(var book=workbook(Map.of())) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(13).getNumericCellValue()).isEqualTo(625000);
            assertThat(book.getSheetAt(0).getRow(4).getCell(8).getNumericCellValue()).isEqualTo(40);
        }
    }
    @Test void noPhasesExcludesScheduleAndCostLightButKeepsCostCells() throws Exception {
        when(costs.calculate(anyLong(),any())).thenReturn(new ProjectCostService.ProjectCost(999999,List.of()));
        when(mapper.sources(any())).thenReturn(List.of(source(1,"단계없음",0,"0","0",null,null,0,0)));
        try (var book=workbook(Map.of())) { assertThat(book.getSheetAt(0).getRow(4).getCell(0).getStringCellValue()).isEqualTo("정상"); }
    }
    @Test void staleIsStrictlyGreaterAndUsesActualStartNotContractDate() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(source(1,"14일",0,"0","0",null,LocalDate.of(2026,10,9),0,0),
            source(2,"15일",0,"0","0",null,LocalDate.of(2026,10,8),0,0),source(3,"없음",0,"0","0",null,null,0,0),
            source(4,"미래",0,"0","0",LocalDate.of(2026,11,1),null,0,0)));
        try (var book=workbook(Map.of("light","주의"))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(4);
            assertThat(book.getSheetAt(0).getRow(4).getCell(1).getStringCellValue()).isEqualTo("15일");
        }
    }
    @Test void emptySetProducesHeaderOnlyWorkbookAndZeroRowMetadata() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of());
        try (var book=workbook(Map.of())) { assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3); }
        verify(mapper).access(argThat(a->a.detail().contains("\"rowCount\":0")));
    }
    @Test void formulaLikeProjectNameIsTextNeverFormula() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(source(1,"=HYPERLINK(\"https://invalid.example\")",1,"0","0",null,null,0,0)));
        try (var book=workbook(Map.of())) { assertThat(book.getSheetAt(0).getRow(4).getCell(1).getCellType()).isEqualTo(CellType.STRING); }
    }
}
