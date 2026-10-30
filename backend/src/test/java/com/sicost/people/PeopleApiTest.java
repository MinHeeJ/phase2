package com.sicost.people;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.costallocation.CostAllocationModels.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.people.PeopleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PeopleController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PeopleCostService.class,
    PeopleExportService.class, ExcelExportService.class, CurrentUser.class, PermissionService.class})
class PeopleApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PeopleMapper mapper;
    @MockBean CostAllocationService allocation;
    @MockBean Clock clock;
    final Clock fixed = Clock.fixed(Instant.parse("2026-02-28T12:00:00Z"), ZoneId.of("Asia/Seoul"));
    static PersonMonth person(long id, String month, boolean leave, boolean payroll) {
        return new PersonMonth(id, "개발자" + id, null, null, "MEMBER", "FT", "DIRECT", 1L,
            "DEV", "SENIOR", month, leave, payroll, payroll ? 100 : 0, 0, 0, 0, 0,
            payroll ? 100 : 0, 0, payroll ? 100 : 0, 0, 0);
    }
    static AllocationRow amount(long id, String month, Long assignment, String type, long cost, long revenue) {
        return new AllocationRow(month, id, assignment, assignment == null ? null : 7L, type,
            assignment == null ? new BigDecimal("0.50") : new BigDecimal("0.50"),
            new BigDecimal("0.50"), new BigDecimal("0.50"), cost, 0, revenue,
            assignment == null ? null : 400L, "N", "N", "N");
    }
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(fixed.instant());
        when(clock.getZone()).thenReturn(fixed.getZone());
        when(clock.withZone(any())).thenReturn(fixed);
        when(mapper.personExists(1)).thenReturn(true);
        when(mapper.people(any())).thenReturn(List.of(person(1, "2026-01", false, true), person(1, "2026-02", false, false)));
        when(mapper.projects(any())).thenReturn(List.of(new ProjectLink(9, 1, 7, "업무", "MO", "DEV", "2026-01-01", "2026-01-31")));
        when(allocation.year(2026, null)).thenReturn(List.of(
            new Allocation("2026-01", "2026-02", false, "원", List.of(), List.of(
                amount(1, "2026-01", 9L, "PROJECT", 50, 200), amount(1, "2026-01", null, "IDLE", 50, 0)), 0),
            new Allocation("2026-02", "2026-02", false, "원", List.of("단가 미등록"), List.of(), 0)));
    }
    @Test void anonymousListUsesEnvelopeAndDefaultsAndMissingMonthWarning() throws Exception {
        mvc.perform(get("/api/costs/people").param("year", "2026").accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.items[0].tot_cst_amt").value(100))
            .andExpect(jsonPath("$.data.items[0].prft_amt").value(100))
            .andExpect(jsonPath("$.data.items[0].expandable").value(true))
            .andExpect(jsonPath("$.data.warnings.length()").value(2));
        verify(mapper, never()).download(any());
    }
    @Test void absentAcceptStillReturnsJson() throws Exception {
        mvc.perform(get("/api/costs/people").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"));
    }
    @Test void averageIncludesMissingPayrollMonthInDivisor() throws Exception {
        mvc.perform(get("/api/costs/people").param("year", "2026").param("view", "AVERAGE").accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].slry_amt").value(50))
            .andExpect(jsonPath("$.data.items[0].prft_amt").value(50))
            .andExpect(jsonPath("$.data.items[0].mm_qty").value(0.25))
            .andExpect(jsonPath("$.data.summary.utilization_rt").value(50.0))
            .andExpect(jsonPath("$.data.summary.earning_rt").value(50.0));
    }
    @Test void filtersCountSummaryAndExportReferToSameFullPopulation() throws Exception {
        mvc.perform(get("/api/costs/people").param("year", "2026").param("empType", "OS").accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.summary.psn_cnt").value(0))
            .andExpect(jsonPath("$.data.summary.avg_price_amt").isEmpty());
        var response = mvc.perform(get("/api/costs/people").param("year", "2026").param("page", "1")
            .accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
            .andReturn().getResponse();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("개발자1");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(3).getCell(22).getStringCellValue()).isEqualTo("실지급액");
            assertThat(sheet.getRow(4).getCell(8).getCellStyle().getDataFormatString()).isEqualTo("0.00");
            assertThat(sheet.getRow(4).getCell(18).getNumericCellValue()).isEqualTo(100);
        }
        verify(mapper).download(argThat(d -> d.actor() == null && d.description().contains("B-W")));
    }
    @ParameterizedTest @ValueSource(strings = {"empType=OS", "partId=2", "vendorId=2", "contractType=TK", "job=PM", "lossOnly=true", "overlapOnly=true"})
    void eachFilterAffectsTotalAndSummary(String filter) throws Exception {
        String[] pair = filter.split("=");
        mvc.perform(get("/api/costs/people").param("year", "2026").param(pair[0], pair[1]).accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.summary.psn_cnt").value(0));
    }
    @Test void emptyOptionalFiltersAreAbsent() throws Exception {
        mvc.perform(get("/api/costs/people").param("year", "2026").param("partId", " ").param("job", "").accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void requiredYearIsValidatedOnBothOperations() throws Exception {
        for (String url : List.of("/api/costs/people", "/api/costs/people/1/breakdown")) {
            mvc.perform(get(url).accept("application/json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("year"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"year=2027", "year=x", "year=0", "view=MONTH", "partId=-1", "vendorId=x", "page=-1", "size=0", "sort=x,asc", "lossOnly=yes"})
    void invalidQueryIs400BeforeDataAccess(String text) throws Exception {
        String[] pair = text.split("=");
        var request = get("/api/costs/people").accept("application/json");
        if (!pair[0].equals("year")) request.param("year", "2026");
        mvc.perform(request.param(pair[0], pair[1])).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(allocation);
    }
    @Test void breakdownShowsMonthlyProjectAndIdleSourcesWithoutWrites() throws Exception {
        mvc.perform(get("/api/costs/people/1/breakdown").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.dev_id").value(1))
            .andExpect(jsonPath("$.data.items[0].prj_nm").value("업무"))
            .andExpect(jsonPath("$.data.items[1].prj_nm").value("대기"))
            .andExpect(jsonPath("$.data.items[1].idle_cst_amt").value(50));
        verify(mapper, never()).download(any());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidPersonIdentifierIs400(String id) throws Exception {
        mvc.perform(get("/api/costs/people/" + id + "/breakdown").param("year", "2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("devId"));
    }
    @Test void missingPersonIs404() throws Exception {
        mvc.perform(get("/api/costs/people/99/breakdown").param("year", "2026"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void closedSnapshotFailureIsNotSilentlyRecalculated() throws Exception {
        when(allocation.year(2026, null)).thenThrow(ApiException.notFound("마감 배분자료 없음: 2026-01"));
        mvc.perform(get("/api/costs/people").param("year", "2026").accept("application/json"))
            .andExpect(status().isNotFound());
        verify(mapper, never()).download(any());
    }
}
