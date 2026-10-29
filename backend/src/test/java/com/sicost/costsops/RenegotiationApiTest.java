package com.sicost.costsops;

import com.sicost.common.api.*;
import com.sicost.common.costallocation.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.costsops.RenegotiationModels.*;
import static com.sicost.common.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {RenegotiationController.class, CostAllocationController.class})
@Import({RequestIdFilter.class, ApiExceptionHandler.class, RenegotiationService.class,
    RenegotiationExportService.class, CostAllocationService.class, CostAllocationEngine.class,
    ExcelExportService.class, CurrentUser.class, RenegotiationApiTest.Time.class})
class RenegotiationApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-28T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired RenegotiationService service;
    @MockBean RenegotiationMapper mapper;
    @MockBean CostAllocationMapper allocations;
    @MockBean PermissionService permissions;
    static Candidate candidate(long assignment, long person, long price) {
        return new Candidate(assignment, person, "개발자 " + person, "FT", 31, "유지보수",
            LocalDate.parse("2026-11-01"), price);
    }
    static Allocation allocation(long assignment, long person, String mm, Long labor, long expense) {
        return new Allocation("2026-10", person, 31L, assignment, "PROJECT", new BigDecimal(mm),
            new BigDecimal(mm), BigDecimal.ONE, labor, expense, 100L, 100L, "N", "N", "N");
    }
    @BeforeEach void setup() {
        when(mapper.candidates(any())).thenReturn(List.of(candidate(17, 7, 100)));
        when(allocations.people("2026-10")).thenReturn(List.of(new Person(7, "FL", "DIRECT", 90L)));
        when(allocations.assignments("2026-10")).thenReturn(List.of(new Assignment(17, 7, 31,
            BigDecimal.ONE, "ACTIVE", "MO", "RUN", "NEW", 100L, null)));
        when(permissions.can(anyString())).thenReturn(true);
    }
    @Test void anonymousDefaultQueryCalculatesWithoutWriting() throws Exception {
        mvc.perform(get("/api/costs/renegotiations"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.referenceMonth").value("2026-10"))
            .andExpect(jsonPath("$.data.unit").value("KRW"))
            .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].margin_rt").value(10))
            .andExpect(jsonPath("$.data.items[0].target_price_amt").value(10000))
            .andExpect(jsonPath("$.data.items[0].required_raise_rt").value(12.5))
            .andExpect(jsonPath("$.data.items[0].annual_profit_increase_amt").value(60))
            .andExpect(jsonPath("$.data.items[0].target_annual_profit_increase_amt").value(150));
        verify(mapper).candidates(new Population(LocalDate.parse("2026-10-29"), LocalDate.parse("2027-04-29"), null, null));
        verify(permissions).can("UI-32.read");
        verify(mapper, never()).download(any());
    }
    @ParameterizedTest @CsvSource({"raiseRate,0,0", "raiseRate,50,600"})
    void raiseBoundariesAreAccepted(String field, String value, int effect) throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param(field, value)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].annual_profit_increase_amt").value(effect));
    }
    @ParameterizedTest @CsvSource({"windowMonths,0", "windowMonths,5", "windowMonths,6.5", "windowMonths,abc",
        "marginBelow,0", "marginBelow,14", "marginBelow,NaN", "raiseRate,-0.1", "raiseRate,50.1", "raiseRate,Infinity",
        "empType,OTHER", "projectId,0", "projectId,01", "projectId,9223372036854775808",
        "page,-1", "page,abc", "size,30", "sort,bad"})
    void invalidFilterHasLocalValidationEnvelope(String field, String value) throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param(field, value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper, allocations);
    }
    @Test void invalidSortDirectionAndFieldAreRejected() throws Exception {
        for (String sort : List.of("dev_nm,up", "sql,asc", "end_dt,asc,extra"))
            mvc.perform(get("/api/costs/renegotiations").param("sort", sort)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("sort"));
    }
    @Test void unknownProjectIsNotFoundBeforeAllocation() throws Exception {
        when(mapper.project(999)).thenReturn(null);
        mvc.perform(get("/api/costs/renegotiations").param("projectId", "999"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(allocations);
    }
    @Test void populationFiltersAndPagingKeepSummaryOverFullFilteredSet() {
        when(mapper.project(31)).thenReturn(31L);
        when(allocations.project(31)).thenReturn(31L);
        var rows = new ArrayList<Candidate>();
        for (int i = 1; i <= 25; i++) rows.add(candidate(i, 7, 100));
        when(mapper.candidates(any())).thenReturn(rows);
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");
        when(allocations.snapshots(any())).thenReturn(rows.stream()
            .map(c -> allocation(c.asgn_id(), c.dev_id(), "1.00", 90L, 0)).toList());
        var result = service.list(Map.of("windowMonths", "3", "marginBelow", "20", "empType", "FT",
            "projectId", "31", "page", "1", "size", "20", "sort", "margin_rt,desc"));
        assertThat(result.items()).hasSize(5).extracting(Row::asgn_id).containsExactly(21L, 22L, 23L, 24L, 25L);
        assertThat(result.total()).isEqualTo(25);
        assertThat(result.summary().people_count()).isEqualTo(1);
        assertThat(result.summary().annual_profit_increase_amt()).isEqualByComparingTo("1500");
        verify(mapper).candidates(new Population(LocalDate.parse("2026-10-29"), LocalDate.parse("2027-01-29"), "FT", 31L));
    }
    @Test void marginThresholdIsStrictAndWeightedNotArithmeticAverage() {
        when(mapper.candidates(any())).thenReturn(List.of(candidate(17, 7, 100), candidate(18, 8, 1000), candidate(19, 9, 100)));
        when(allocations.monthStatus(anyString())).thenReturn("CLOSED");
        when(allocations.snapshots(any())).thenReturn(List.of(allocation(17, 7, "1", 90L, 0),
            new Allocation("2026-10", 8, 31L, 18L, "PROJECT", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, 1000L, 0, 1000L, 1000L, "N", "N", "N"), allocation(19, 9, "1", 85L, 0)));
        var result = service.list(Map.of());
        assertThat(result.items()).extracting(Row::asgn_id).containsExactly(17L, 18L);
        assertThat(result.summary().margin_rt()).isBetween(new BigDecimal("0.9090"), new BigDecimal("0.9091"));
    }
    @Test void zeroMmAndMissingSalaryAreUnavailableNotCredibleZero() throws Exception {
        when(allocations.assignments(anyString())).thenReturn(List.of(new Assignment(17, 7, 31,
            BigDecimal.ZERO, "ACTIVE", "MO", "RUN", "NEW", 0L, null)));
        when(allocations.people(anyString())).thenReturn(List.of(new Person(7, "FL", "DIRECT", null)));
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].margin_rt").isEmpty())
            .andExpect(jsonPath("$.data.items[0].target_price_amt").isEmpty())
            .andExpect(jsonPath("$.data.summary.margin_rt").isEmpty())
            .andExpect(jsonPath("$.data.warnings").isNotEmpty());
    }
    @Test void missingClosedSnapshotIsConflictWithoutReadingOpenInputs() throws Exception {
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");
        when(allocations.missingSnapshots(any())).thenReturn(1L);
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"))
            .andExpect(jsonPath("$.message").value("마감 배분자료 없음"));
        verify(allocations, never()).people(anyString());
    }
    @Test void closedSnapshotPriceIsNeverReplacedByLivePrice() {
        when(mapper.candidates(any())).thenReturn(List.of(candidate(17, 7, 999999)));
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");
        when(allocations.snapshots(any())).thenReturn(List.of(allocation(17, 7, "1", 90L, 0)));
        assertThat(service.list(Map.of()).items().get(0).bill_untprc_amt()).isEqualTo(100L);
        verify(allocations, never()).assignments(anyString());
    }
    @Test void openRequeryUsesNewPriceWithoutWritingSourcesOrSnapshot() {
        assertThat(service.list(Map.of()).items()).hasSize(1);
        when(mapper.candidates(any())).thenReturn(List.of(candidate(17, 7, 200)));
        assertThat(service.list(Map.of()).items()).isEmpty();
        verify(mapper, never()).download(any());
    }
    @Test void emptyPopulationReturnsTypedObject() throws Exception {
        when(mapper.candidates(any())).thenReturn(List.of());
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.summary.margin_rt").isEmpty());
    }
    @Test void emptyClosedPopulationDoesNotReadOrRecomputeUnrelatedDevelopers() throws Exception {
        when(mapper.candidates(any())).thenReturn(List.of());
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CLOSED"));
        verifyNoInteractions(allocations);
    }
    @Test void missingRelevantAssignmentSnapshotCannotBeReplacedWithCurrentPrice() throws Exception {
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");
        when(allocations.snapshots(any())).thenReturn(List.of());
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(allocations, never()).people(anyString());
    }
    @Test void nullMarginsStayLastEvenForDescendingSort() {
        when(mapper.candidates(any())).thenReturn(List.of(candidate(18, 8, 100), candidate(17, 7, 100)));
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");
        when(allocations.snapshots(any())).thenReturn(List.of(allocation(18, 8, "1", null, 0), allocation(17, 7, "1", 90L, 0)));
        assertThat(service.list(Map.of("sort", "margin_rt,desc")).items()).extracting(Row::asgn_id).containsExactly(17L, 18L);
    }
    @Test void allocationEndpointRemainsOwnedBySharedControllerAndAnonymous() throws Exception {
        mvc.perform(get("/api/costs/allocation").param("month", "2026-10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(90));
        verifyNoInteractions(permissions, mapper);
    }
    @Test void downloadIsRealXlsxFullPopulationAndOneAuditWithNullActor() throws Exception {
        var response = mvc.perform(get("/api/costs/renegotiations").param("page", "1").param("size", "20")
            .accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
            .andReturn().getResponse();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(0).getStringCellValue()).isEqualTo("개발자 7");
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(10).getNumericCellValue()).isEqualTo(60);
        }
        verify(mapper, times(1)).download(argThat(a -> a.actor() == null && a.detail().contains("columns")));
        verify(permissions).can("UI-32.download");
    }
    @Test void auditFailureNeverReturnsSuccessfulDownload() throws Exception {
        doThrow(new IllegalStateException("audit failed")).when(mapper).download(any());
        mvc.perform(get("/api/costs/renegotiations").accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(status().isInternalServerError()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("SERVER_ERROR"));
    }
    @Test void spreadsheetFormulaLookingNamesRemainLiteralText() throws Exception {
        when(mapper.candidates(any())).thenReturn(List.of(new Candidate(17, 7, "=HYPERLINK(\"https://invalid.example\")", "FT", 31,
            "유지보수", LocalDate.parse("2026-11-01"), 100L)));
        var bytes = mvc.perform(get("/api/costs/renegotiations")
            .accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
        }
    }
}
