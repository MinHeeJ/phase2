package com.sicost.costsops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.people.*;
import com.sicost.projects.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {PeopleController.class, CostProjectController.class, RenegotiationController.class})
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PeopleExportService.class,
    CostProjectExportService.class, RenegotiationExportService.class, ExcelExportService.class,
    CurrentUser.class, PermissionService.class})
class CostWorkbookApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PeopleCostService people;
    @MockBean CostProjectService projects;
    @MockBean RenegotiationService renegotiations;
    @MockBean PeopleMapper peopleMapper;
    @MockBean CostProjectMapper projectMapper;
    @MockBean RenegotiationMapper renegotiationMapper;
    @MockBean Clock clock;

    static final String XLSX = RenegotiationExportService.XLSX;
    static final String FORMULA_TEXT = "=HYPERLINK(\"https://invalid.example\",\"실행 금지\")";

    @BeforeEach void setup() throws Exception {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(fixed.instant());
        when(clock.getZone()).thenReturn(fixed.getZone());
        when(clock.withZone(any())).thenAnswer(i -> fixed.withZone(i.getArgument(0)));
        rows(false);
    }

    void rows(boolean empty) throws Exception {
        var person = json.readValue("""
            {"dev_id":91,"dev_nm":"인력","emp_se_cd":"FT","project_names":["업무"],
             "period":"2026-01 ~ 2026-10","contract_types":["MO"],"mm_qty":0.5,
             "bill_untprc_amt":1000000,"tot_cst_amt":450000,"prft_amt":50000,"mrgn_rt":10}
            """, PeopleModels.PeopleRow.class);
        var project = json.readValue("""
            {"prj_id":42,"prj_no":"P-42","prj_nm":"업무","cntrct_ty_cd":"MO",
             "confirmed_supply_amt":500000,"cumulative_cost_amt":450000,"profit_amt":50000,
             "margin_rt":10,"plan_mm_qty":0.5,"actual_mm_qty":0.5}
            """, CostProjectModels.Row.class);
        var personNode = json.valueToTree(person);
        ((com.fasterxml.jackson.databind.node.ObjectNode) personNode).put("dev_nm", FORMULA_TEXT);
        person = json.treeToValue(personNode, PeopleModels.PeopleRow.class);
        var projectNode = json.valueToTree(project);
        ((com.fasterxml.jackson.databind.node.ObjectNode) projectNode).put("prj_nm", FORMULA_TEXT);
        project = json.treeToValue(projectNode, CostProjectModels.Row.class);
        var detail = new CostProjectModels.Detail("2026-10", "2026-10", false, "원", List.of(),
            project, List.of(), List.of(), null);
        var renegotiation = new RenegotiationModels.Row(73, 91, FORMULA_TEXT, "FT", 42, "업무",
            LocalDate.of(2026,12,31), 63, new BigDecimal("0.50"), 1000000L, 450000,
            BigDecimal.TEN, 1130000L, new BigDecimal("12.5"), 300000L, 780000L);
        when(people.full(any())).thenReturn(new PeopleModels.PeopleList("2026-10", "2026-10", false,
            "원", List.of(), empty ? List.of() : List.of(person), empty ? 0 : 1, 1, 20, null));
        when(projects.filtered(any())).thenReturn(empty ? List.of() : List.of(detail));
        when(renegotiations.list(any(), eq(true))).thenReturn(new RenegotiationModels.Result(
            "2026-10", "2026-10", false, "원", List.of(), empty ? List.of() : List.of(renegotiation),
            empty ? 0 : 1, 1, 20, null, new BigDecimal("20"), new BigDecimal("5")));
    }

    MockHttpServletRequestBuilder download(String resource) {
        var request = get("/api/costs/" + resource).accept(XLSX).param("page", "1").param("size", "20");
        if (!resource.equals("renegotiations")) request.param("year", "2026");
        return request;
    }

    @ParameterizedTest @ValueSource(strings = {"people", "projects", "renegotiations"})
    void realWorkbookHasCorrectTypeAttachmentNoStoreAndNumericCells(String resource) throws Exception {
        byte[] bytes = mvc.perform(download(resource)).andExpect(status().isOk())
            .andExpect(content().contentType(XLSX)).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
            .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(4);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            int textColumn = resource.equals("people") ? 1 : resource.equals("projects") ? 2 : 0;
            var text = sheet.getRow(4).getCell(textColumn);
            assertThat(text.getCellType()).isEqualTo(CellType.STRING);
            assertThat(text.getStringCellValue()).isEqualTo(FORMULA_TEXT);
            int moneyColumn = resource.equals("people") ? 17 : resource.equals("projects") ? 12 : 7;
            assertThat(sheet.getRow(4).getCell(moneyColumn).getNumericCellValue()).isEqualTo(450000);
            assertThat(book.getExternalLinksTable()).isEmpty();
        }
        audit(resource, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"people", "projects", "renegotiations"})
    void emptyPopulationProducesHeaderOnlyWorkbookAndZeroRowAudit(String resource) throws Exception {
        rows(true);
        byte[] bytes = mvc.perform(download(resource)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3);
            assertThat(book.getSheetAt(0).getRow(3).getPhysicalNumberOfCells()).isPositive();
        }
        audit(resource, 0);
    }

    @ParameterizedTest @ValueSource(strings = {"people", "projects", "renegotiations"})
    void downloadForwardsAppliedFiltersWithoutReplacingThemWithPageResults(String resource) throws Exception {
        var request = download(resource);
        if (resource.equals("people")) request.param("empType", "FL").param("partId", "42").param("view", "AVERAGE");
        else if (resource.equals("projects")) request.param("partId", "42").param("q", "업무").param("type", "MO");
        else request.param("projectId", "42").param("windowMonths", "3").param("raiseRate", "10");
        mvc.perform(request).andExpect(status().isOk());
        switch (resource) {
            case "people" -> verify(people).full(argThat(q -> q.year()==2026 && q.view().equals("AVERAGE")
                && q.filters().empType().equals("FL") && q.filters().partId()==42L && q.paging().page()==1));
            case "projects" -> verify(projects).filtered(argThat(q -> q.year()==2026 && q.partId()==42L
                && q.q().equals("업무") && q.type().equals("MO") && q.paging().page()==1));
            default -> verify(renegotiations).list(argThat(q -> q.filters().projectId()==42L
                && q.filters().windowMonths()==3 && q.filters().raiseRate().compareTo(BigDecimal.TEN)==0
                && q.paging().page()==1), eq(true));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"people", "projects", "renegotiations"})
    void invalidFilterIsJson400WithoutSourceOrAuditCalls(String resource) throws Exception {
        var request = download(resource).param("sort", "invalid");
        mvc.perform(request).andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value("sort"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(people, projects, renegotiations, peopleMapper, projectMapper, renegotiationMapper);
    }

    @ParameterizedTest @ValueSource(strings = {"people", "projects", "renegotiations"})
    void unavailableClosedSnapshotRemainsConflictAndDoesNotAuditSuccess(String resource) throws Exception {
        var failure = ApiException.conflict("PART_STANDARD_MISSING", "마감 배분자료 없음", "month");
        switch (resource) {
            case "people" -> when(people.full(any())).thenThrow(failure);
            case "projects" -> when(projects.filtered(any())).thenThrow(failure);
            default -> when(renegotiations.list(any(), eq(true))).thenThrow(failure);
        }
        mvc.perform(download(resource)).andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"))
            .andExpect(header().doesNotExist("Content-Disposition"));
        verifyNoInteractions(peopleMapper, projectMapper, renegotiationMapper);
    }

    void audit(String resource, int count) {
        switch (resource) {
            case "people" -> verify(peopleMapper).download(argThat(d -> d.actor()==null
                && d.name().equals("개인원가수익.xlsx") && d.description().contains("\"rows\":"+count)));
            case "projects" -> verify(projectMapper).download(new CostProjectModels.Download(
                "프로젝트원가분석.xlsx", "{\"rows\":"+count+"}", null));
            default -> verify(renegotiationMapper).download(new RenegotiationModels.Download(
                "단가재협상.xlsx", "{\"rows\":"+count+"}", null));
        }
    }
}
