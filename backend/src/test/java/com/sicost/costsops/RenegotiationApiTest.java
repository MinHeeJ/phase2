package com.sicost.costsops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.costallocation.*;
import com.sicost.shared.SharedReadMapper;
import com.sicost.shared.SharedReadModels.OperatingParam;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.costallocation.CostAllocationModels.*;
import static com.sicost.costsops.RenegotiationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = RenegotiationController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, RenegotiationService.class,
    RenegotiationExportService.class, CostAllocationService.class, ExcelExportService.class,
    CurrentUser.class, PermissionService.class})
class RenegotiationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean RenegotiationMapper mapper;
    @MockBean CostAllocationMapper allocations;
    @MockBean SharedReadMapper parameters;
    @MockBean Clock clock;

    static OperatingParam parameter(String key, String value) {
        return new OperatingParam(key, "2026-01", key, new BigDecimal(value), null, "%", null);
    }
    static Source source(long id) {
        return new Source(id, id, "개발자 " + id, "FT", id, "프로젝트 " + id, LocalDate.of(2027, 1, 1));
    }
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-29T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        when(parameters.effectiveParam(eq("TARGET_MARGIN_RATE"), anyString())).thenReturn(parameter("TARGET_MARGIN_RATE", "20"));
        when(parameters.effectiveParam(eq("RENEGO_RAISE_RATE"), anyString())).thenReturn(parameter("RENEGO_RAISE_RATE", "5"));
        when(allocations.projectExists(anyLong())).thenReturn(true);
        when(allocations.people("2026-10")).thenReturn(List.of(new Person(1, "FT", "DIRECT", 900L), new Person(2, "FT", "DIRECT", 1900L)));
        when(allocations.assignments("2026-10")).thenReturn(List.of(
            new Assignment(1, 1, 1, "MO", "RUN", "NEW", "ACTIVE", new BigDecimal("1.00"), 1000L),
            new Assignment(2, 2, 2, "DP", "RUN", "NEW", "ACTIVE", new BigDecimal("1.00"), 2000L)));
        when(mapper.candidates(any())).thenAnswer(i -> {
            SourceQuery q = i.getArgument(0);
            return List.of(source(1), source(2)).stream()
                .filter(r -> q.projectId() == null || q.projectId() == r.prj_id()).toList();
        });
    }
    @Test void anonymousDefaultReadCalculatesAllCardsAndRowsWithoutWriting() throws Exception {
        mvc.perform(get("/api/costs/renegotiations"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.calculation_month").value("2026-10"))
            .andExpect(jsonPath("$.data.summary.psn_cnt").value(2))
            .andExpect(jsonPath("$.data.summary.weighted_margin_rt").value(6.7))
            .andExpect(jsonPath("$.data.summary.assumed_annual_profit_increase_amt").value(1800))
            .andExpect(jsonPath("$.data.items[0].target_price_amt").value(10000))
            .andExpect(jsonPath("$.data.items[0].required_raise_rt").value(12.5));
        verify(mapper).candidates(new SourceQuery(LocalDate.of(2026,10,29), LocalDate.of(2027,4,29), "2026-10", null, null));
        verify(mapper, never()).download(any());
    }
    @Test void strictMarginThresholdExcludesEqualAndSummaryUsesFilteredSet() throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param("marginBelow", "10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].asgn_id").value(2))
            .andExpect(jsonPath("$.data.summary.psn_cnt").value(1));
    }
    @Test void projectFilterAndCountUseSameScope() throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param("projectId", "2").param("empType", "FT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].prj_id").value(2));
        verify(mapper).candidates(new SourceQuery(LocalDate.of(2026,10,29), LocalDate.of(2027,4,29), "2026-10", "FT", 2L));
    }
    @Test void pageOutsideResultKeepsFullSummaryAndTotal() throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param("page", "1").param("size", "20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.summary.psn_cnt").value(2));
    }
    @Test void raiseRecomputesWithoutChangingSourcesOrAuditing() throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param("raiseRate", "50"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.assumed_annual_profit_increase_amt").value(18000));
        mvc.perform(get("/api/costs/renegotiations").param("raiseRate", "0"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.assumed_annual_profit_increase_amt").value(0));
        verify(mapper, never()).download(any());
    }
    @ParameterizedTest @CsvSource({"windowMonths,4", "windowMonths,0", "marginBelow,14", "raiseRate,-0.1", "raiseRate,50.1", "raiseRate,NaN", "empType,X", "projectId,0", "projectId,abc", "projectId,-1", "page,-1", "size,1", "sort,unknown"})
    void invalidFilterIs400BeforeQueries(String field, String value) throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param(field, value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value(field));
        verifyNoInteractions(mapper, allocations);
    }
    @Test void missingProjectIs404() throws Exception {
        when(allocations.projectExists(99)).thenReturn(false);
        mvc.perform(get("/api/costs/renegotiations").param("projectId", "99"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(mapper);
    }
    @Test void closedMonthUsesSavedPriceMmAndCostForEveryAssumption() throws Exception {
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");
        var saved = new AllocationRow("2026-10",1L,1L,1L,"PROJECT",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,950,0,1000,1000L,"N","N","N");
        when(allocations.snapshots("2026-10")).thenReturn(List.of(saved));
        doReturn(List.of(source(1))).when(mapper).candidates(any());
        mvc.perform(get("/api/costs/renegotiations").param("raiseRate","10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.closed").value(true))
            .andExpect(jsonPath("$.data.items[0].allocated_cost_amt").value(950))
            .andExpect(jsonPath("$.data.items[0].assumed_annual_profit_increase_amt").value(1200));
        verify(allocations,never()).people(anyString());
        verify(allocations,never()).assignments(anyString());
    }
    @Test void missingClosedSnapshotIs409WithoutFallback() throws Exception {
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");
        when(allocations.expectedCost(any())).thenReturn(true);
        mvc.perform(get("/api/costs/renegotiations")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"));
        verify(allocations,never()).people(anyString());
    }
    @Test void emptyOptionalTextHasNoPredicateAndMissingParametersWarn() throws Exception {
        when(parameters.effectiveParam(anyString(),anyString())).thenReturn(null);
        mvc.perform(get("/api/costs/renegotiations").param("empType", "").param("projectId", ""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.target_margin_rate").isEmpty())
            .andExpect(jsonPath("$.data.warnings[0]").value("기준값 없음: TARGET_MARGIN_RATE"));
    }
    @Test void effectiveRaiseParameterSuppliesDefaultWithoutOverridingExplicitAssumption() throws Exception {
        when(parameters.effectiveParam(eq("RENEGO_RAISE_RATE"), anyString())).thenReturn(parameter("RENEGO_RAISE_RATE", "7"));
        mvc.perform(get("/api/costs/renegotiations"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.raise_rate").value(7))
            .andExpect(jsonPath("$.data.summary.assumed_annual_profit_increase_amt").value(2520));
        mvc.perform(get("/api/costs/renegotiations").param("raiseRate", "0"))
            .andExpect(jsonPath("$.data.raise_rate").value(0));
    }
    @Test void sortDescendingStillUsesAscendingIdentityForEqualValues() throws Exception {
        mvc.perform(get("/api/costs/renegotiations").param("sort", "end_dt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].asgn_id").value(1))
            .andExpect(jsonPath("$.data.items[1].asgn_id").value(2));
    }
    @Test void emptyCandidatesHaveZeroCountAndNullWeightedMargin() throws Exception {
        doReturn(List.of()).when(mapper).candidates(any());
        mvc.perform(get("/api/costs/renegotiations"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.summary.psn_cnt").value(0))
            .andExpect(jsonPath("$.data.summary.weighted_margin_rt").isEmpty())
            .andExpect(jsonPath("$.data.summary.assumed_annual_profit_increase_amt").value(0));
    }
    @Test void sourcePriceMissingKeepsNullEffectsAndSpecificWarning() throws Exception {
        when(allocations.assignments("2026-10")).thenReturn(List.of(
            new Assignment(1, 1, 1, "MO", "RUN", "NEW", "ACTIVE", BigDecimal.ONE, null)));
        doReturn(List.of(source(1))).when(mapper).candidates(any());
        mvc.perform(get("/api/costs/renegotiations"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].margin_rt").isEmpty())
            .andExpect(jsonPath("$.data.items[0].assumed_annual_profit_increase_amt").isEmpty())
            .andExpect(jsonPath("$.data.summary.weighted_margin_rt").isEmpty());
    }
    @Test void xlsxAcceptExportsAllFilteredRowsNotPageAndAuditsAnonymousDownload() throws Exception {
        byte[] bytes = mvc.perform(get("/api/costs/renegotiations").param("page", "1").param("size", "20")
                .accept(RenegotiationExportService.XLSX)).andExpect(status().isOk())
            .andExpect(content().contentType(RenegotiationExportService.XLSX))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString(".xlsx")))
            .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(5);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("2026-10", "windowMonths=6", "12개월");
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("개발자 1");
        }
        verify(mapper).download(new Download("단가재협상.xlsx", "{\"rows\":2}", null));
    }
}
