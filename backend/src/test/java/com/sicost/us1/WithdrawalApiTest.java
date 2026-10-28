package com.sicost.us1;

import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.apiops.ProjectBoardModels.OperatingParam;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.CurrentUser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import org.apache.poi.ss.usermodel.DateUtil;
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
import static com.sicost.us1.WithdrawalExamples.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WithdrawalController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, WithdrawalService.class,
    WithdrawalExportService.class, ExcelExportService.class, CurrentUser.class, WithdrawalApiTest.Time.class})
class WithdrawalApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean WithdrawalMapper mapper;
    @MockBean ProjectBoardMapper parameters;
    @BeforeEach void setup() {
        when(mapper.sources()).thenReturn(List.of(source(1, 1, "2026-11-20", null, null)));
        when(parameters.params(anyString())).thenReturn(List.of(
            new OperatingParam("DISPATCH_MAX_MONTHS", "2000-01", "파견 최대", new BigDecimal("24"), null, "개월", "UI-05"),
            new OperatingParam("DISPATCH_ALERT_DAYS", "2000-01", "파견 알림", null, "60,30", "일", "UI-05")));
    }
    @Test void anonymousReadIncludesWireFieldsAndSeoulToday() throws Exception {
        mvc.perform(get("/api/withdrawals").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.today").value("2026-10-14"))
            .andExpect(jsonPath("$.data.rows[0].asgn_id").value(1))
            .andExpect(jsonPath("$.data.rows[0].remaining_days").value(37))
            .andExpect(jsonPath("$.data.rows[0].extend_count").value(1))
            .andExpect(jsonPath("$.data.rows[0].early_end").value(true))
            .andExpect(jsonPath("$.data.rows[0].updated_at").value("2026-10-14T00:00:00.123456"))
            .andExpect(jsonPath("$.data.summary.noNextPeople").value(1));
        verify(parameters).params("2026-10");
    }
    @ParameterizedTest @ValueSource(strings = {"year=0", "year=10000", "year=abc", "page=-1", "page=2147483648", "size=20", "size=100", "period=UNKNOWN", "ext=UNKNOWN", "job=bad!", "sort=end_dt,down", "sort=end_dt,asc;", "sort=salary,asc", "dispatchWithin60=yes"})
    void invalidQueryReturnsSingularField(String entry) throws Exception {
        String[] pair = entry.split("=", 2);
        var request = get("/api/withdrawals");
        if (!pair[0].equals("year")) request.param("year", "2026");
        mvc.perform(request.param(pair[0], pair[1]))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(pair[0]));
    }
    @Test void missingYearIs400() throws Exception {
        mvc.perform(get("/api/withdrawals")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
    }
    @Test void dispatchTooltipUsesEffectiveDatabaseAlertsAndSharedDisplayRules() throws Exception {
        var original = source(1, 1, "2026-11-19", null, null);
        when(mapper.sources()).thenReturn(List.of(new WithdrawalModels.Source(
            original.asgn_id(), original.asgn_no(), original.dev_id(), original.dev_nm(),
            original.mng_no(), original.prj_id(), original.prj_nm(), original.job_cd(), original.job_nm(),
            LocalDate.of(2024, 11, 20), original.end_dt(), original.plan_end_dt(),
            original.ext_psblty_cd(), original.ext_psblty_nm(), original.asgn_stts_cd(),
            original.updated_at(), original.extend_count(), original.early_end(), original.replacement_name(),
            "DP", original.prj_stts_cd(), original.use_yn(), LocalDate.of(2024, 11, 20),
            original.next_asgn_id(), original.next_prj_nm(), original.next_bgn_dt())));
        mvc.perform(get("/api/withdrawals").param("year", "2026").param("dispatchWithin60", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rows[0].dispatch_display").value("파견 만료 D-36"))
            .andExpect(jsonPath("$.data.rows[0].dispatch_tone").value("warning"))
            .andExpect(jsonPath("$.data.rows[0].dispatch_alert_dates[0]").value("2026-09-20"))
            .andExpect(jsonPath("$.data.rows[0].dispatch_alert_dates[1]").value("2026-10-20"));
    }
    @Test void combinedFiltersDoNotChangeGlobalSummary() throws Exception {
        mvc.perform(get("/api/withdrawals").param("year", "2025").param("job", "MISSING").param("ext", "NONE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows").isEmpty())
            .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.summary.soonPeople").value(1));
    }
    @Test void fiftyRowsArePagedAfterFilteringAndStableSorting() throws Exception {
        when(mapper.sources()).thenReturn(LongStream.rangeClosed(1, 61)
            .mapToObj(i -> source(i, 1, "2026-11-20", null, null)).toList());
        mvc.perform(get("/api/withdrawals").param("year", "2026").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(61))
            .andExpect(jsonPath("$.data.rows.length()").value(11))
            .andExpect(jsonPath("$.data.rows[0].asgn_id").value(51))
            .andExpect(jsonPath("$.data.summary.soonPeople").value(1));
    }
    @Test void doneOnlyReturnsPastRows() throws Exception {
        when(mapper.sources()).thenReturn(List.of(source(1, 1, "2026-10-13", "H", null), source(2, 2, "2026-10-14", null, null)));
        mvc.perform(get("/api/withdrawals").param("year", "2026").param("period", "DONE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].remaining_days").value(-1))
            .andExpect(jsonPath("$.data.rows[0].next_display").value("–"));
    }
    @Test void searchNameAndManagementNumberAreAndedWithChance() throws Exception {
        mvc.perform(get("/api/withdrawals").param("year", "2026").param("q", "K-1").param("ext", "NONE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/withdrawals").param("year", "2026").param("q", "K-1").param("ext", "H"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/withdrawals").param("year", "2026").param("q", "개발자"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void nonDispatchRowsAreExcludedBeforePaginationByDispatchFilter() throws Exception {
        when(mapper.sources()).thenReturn(LongStream.rangeClosed(1, 61)
            .mapToObj(i -> source(i, 1, "2026-11-20", null, null)).toList());
        mvc.perform(get("/api/withdrawals").param("year", "2026").param("dispatchWithin60", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.rows").isEmpty()).andExpect(jsonPath("$.data.summary.soonPeople").value(1));
    }
    @Test void exportContainsAllRowsTypedDatesAndNoSensitiveColumns() throws Exception {
        when(mapper.sources()).thenReturn(LongStream.rangeClosed(1, 61)
            .mapToObj(i -> source(i, 1, "2026-11-20", null, null)).toList());
        var response = mvc.perform(get("/api/withdrawals/export").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("2026_20261014.xlsx");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(64);
            assertThat(sheet.getRow(4).getCell(10).getStringCellValue()).isEqualTo("미정");
            assertThat(DateUtil.isCellDateFormatted(sheet.getRow(4).getCell(5))).isTrue();
            assertThat(sheet.getRow(4).getCell(5).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 20));
            assertThat(sheet.getRow(3).getLastCellNum()).isEqualTo((short) 12);
        }
    }
    @Test void exportUsesSameFiltersAndSort() throws Exception {
        when(mapper.sources()).thenReturn(List.of(source(1, 1, "2026-11-20", "H", null), source(2, 2, "2026-10-14", null, null)));
        var bytes = mvc.perform(get("/api/withdrawals/export").param("year", "2026").param("ext", "NONE"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(4);
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(2).getStringCellValue()).isEqualTo("A-2");
        }
    }
    @Test void exportRejectsPaginationInsteadOfExportingCurrentPage() throws Exception {
        mvc.perform(get("/api/withdrawals/export").param("year", "2026").param("page", "1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("page"));
    }
    @Test void missingOperatingStandardIsReportedRatherThanDefaulted() throws Exception {
        when(parameters.params(anyString())).thenReturn(List.of());
        mvc.perform(get("/api/withdrawals").param("year", "2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }
}
