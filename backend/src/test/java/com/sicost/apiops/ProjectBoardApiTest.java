package com.sicost.apiops;

import static com.sicost.apiops.ProjectBoardModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
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
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = ProjectBoardController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectBoardService.class,
    ProgressParameterService.class, ProjectCostService.class, PermissionService.class, ProjectBoardApiTest.Time.class})
class ProjectBoardApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectBoardMapper mapper;
    @MockBean ProjectCostService costs;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static List<OperatingParam> params() {
        var keys = List.of("SIG_DELAY_WARN", "SIG_DELAY_RISK", "SIG_COST_WARN", "SIG_COST_RISK", "SIG_STALE_DAYS", "BUDGET_RATE");
        var values = List.of("5", "10", "5", "15", "14", "80");
        var rows = new ArrayList<OperatingParam>();
        for (int i = 0; i < keys.size(); i++) rows.add(new OperatingParam(keys.get(i), "2026-01", keys.get(i), n(values.get(i)), null, "%", null));
        return rows;
    }
    static BoardSource source(long id, String type, String actual, LocalDate memo) {
        return new BoardSource(id, "P-26" + id, "프로젝트 " + id, null, type, id % 2 + 1, "PM 미지정", 500000000L,
            null, memo, actual == null ? null : n(actual), 2, 1, 0, 0, 400000000L);
    }
    static Phase phase(long id, String weight, LocalDate start, LocalDate end, LocalDate complete) {
        return new Phase(id, 1, (int) id, "단계 " + id, n(weight), start, end, complete, null);
    }
    @BeforeEach void setup() {
        when(mapper.params(anyString())).thenReturn(params());
        when(mapper.projects(any())).thenReturn(List.of(source(1, "TK", "42", TODAY)));
        when(mapper.phases(anyLong())).thenReturn(List.of(phase(1, "50.5", TODAY.minusDays(2), TODAY.minusDays(1), null)));
        when(costs.calculate(anyLong(), anyString())).thenReturn(new ProjectCost(230000000, List.of()));
    }
    @Test void anonymousBoardReturnsExactEnvelopeAndCostExample() throws Exception {
        mvc.perform(get("/api/project-board")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50))
            .andExpect(jsonPath("$.data.items[0].plan_prgs_rt").value(50.5))
            .andExpect(jsonPath("$.data.items[0].actl_prgs_rt").value(42.0))
            .andExpect(jsonPath("$.data.items[0].schedule_diff_rt").value(-8.5))
            .andExpect(jsonPath("$.data.items[0].budget_amt").value(400000000))
            .andExpect(jsonPath("$.data.items[0].cost_use_rt").value(57.5))
            .andExpect(jsonPath("$.data.items[0].cost_progress_diff_rt").value(15.5))
            .andExpect(jsonPath("$.data.items[0].light").value("위험"))
            .andExpect(jsonPath("$.data.items[0].currentPhase.status").value("지연"));
        verify(mapper).projects(TODAY); verify(mapper).params("2026-10");
    }
    @Test void dispatchAlertListDoesNotBreakBoardOrDisappearFromSharedParams() throws Exception {
        var rows = new ArrayList<>(params());
        rows.add(new OperatingParam("DISPATCH_MAX_MONTHS", "2026-01", "파견 최대 기간", n("24"), null, "개월", null));
        rows.add(new OperatingParam("DISPATCH_ALERT_DAYS", "2026-01", "파견 만료 알림", null, "60,30", "일", null));
        rows.add(new OperatingParam("PLAN_GAP_TOLERANCE", "2026-01", "계획 대비 허용 범위", n("0.1"), null, "MM", null));
        when(mapper.params(anyString())).thenReturn(rows);
        mvc.perform(get("/api/project-board")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].cost_use_rt").value(57.5))
            .andExpect(jsonPath("$.data.items[0].light").value("위험"));
        mvc.perform(get("/api/params")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(9))
            .andExpect(jsonPath("$.data[7].txt_val").value("60,30"));
    }
    @Test void anonymousParamsDefaultsToSeoulMonth() throws Exception {
        mvc.perform(get("/api/params")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(6))
            .andExpect(jsonPath("$.data[0].oprt_std_id").value("SIG_DELAY_WARN"));
        verify(mapper).params("2026-10");
    }
    @Test void requestedMonthIsPassedWithoutSubstitution() throws Exception {
        mvc.perform(get("/api/params").param("month", "2025-02")).andExpect(status().isOk());
        verify(mapper).params("2025-02");
    }
    @ParameterizedTest @ValueSource(strings = {"2026-13", "2026-1", "", "2026-10-01", "xxxx-10"})
    void invalidMonth(String month) throws Exception {
        mvc.perform(get("/api/params").param("month", month)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings = {"SIG_DELAY_WARN", "SIG_DELAY_RISK", "SIG_COST_WARN", "SIG_COST_RISK", "SIG_STALE_DAYS", "BUDGET_RATE"})
    void everyMissingKeyFailsParamsAndBoardWithoutFabricatingNormal(String key) throws Exception {
        when(mapper.params(anyString())).thenReturn(params().stream().filter(p -> !key.equals(p.oprt_std_id())).toList());
        for (var path : List.of("/api/params", "/api/project-board")) mvc.perform(get(path))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(key)));
        verify(mapper, never()).projects(any());
    }
    @Test void textualNumericIsAcceptedWithoutChangingProjection() throws Exception {
        var rows = new ArrayList<>(params());
        rows.set(0, new OperatingParam("SIG_DELAY_WARN", "2026-01", "주의", null, "5.25", "%p", null));
        when(mapper.params(anyString())).thenReturn(rows);
        mvc.perform(get("/api/params")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].num_val").isEmpty())
            .andExpect(jsonPath("$.data[0].txt_val").value("5.25"));
    }
    @Test void nonNumericParameterIs400() throws Exception {
        var rows = new ArrayList<>(params());
        rows.set(0, new OperatingParam("SIG_DELAY_WARN", "2026-01", "주의", null, "invalid", "%p", null));
        when(mapper.params(anyString())).thenReturn(rows);
        mvc.perform(get("/api/params")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }
    @ParameterizedTest @CsvSource({"light,RED", "type,XX", "part,-1", "part,abc", "page,-1", "page,abc", "size,1", "size,500", "sort,unknown", "sort,'prj_nm,ASC'", "sort,'budget_amt,asc'"})
    void invalidBoardFilter(String field, String value) throws Exception {
        mvc.perform(get("/api/project-board").param(field, value)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper);
    }
    @Test void summaryAndStaleAreIndependentOfCombinedFiltersAndPagination() throws Exception {
        when(mapper.projects(any())).thenReturn(List.of(source(1, "TK", "42", TODAY.minusDays(15)), source(2, "MO", null, TODAY.minusDays(14))));
        mvc.perform(get("/api/project-board").param("light", "정상").param("type", "MO").param("part", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].prj_id").value(2)).andExpect(jsonPath("$.data.summary.project_count").value(2))
            .andExpect(jsonPath("$.data.summary.risk_count").value(1)).andExpect(jsonPath("$.data.summary.open_issue_count").value(4))
            .andExpect(jsonPath("$.data.stale_projects.length()").value(1)).andExpect(jsonPath("$.data.stale_projects[0].memo_elapsed_days").value(15));
        mvc.perform(get("/api/project-board").param("page", "1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.summary.project_count").value(2));
    }
    @Test void sortingHappensBeforePagingAndDefaultsAreDeterministic() throws Exception {
        var rows = new ArrayList<BoardSource>();
        for (int i = 1; i <= 55; i++) rows.add(source(i, "MO", null, TODAY));
        when(mapper.projects(any())).thenReturn(rows);
        var result = mvc.perform(get("/api/project-board").param("sort", "prj_no,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(55))
            .andExpect(jsonPath("$.data.items.length()").value(50)).andReturn();
        var data = json.readTree(result.getResponse().getContentAsByteArray()).path("data");
        assertThat(data.path("items").get(0).path("prj_no").asText()).isEqualTo("P-269");
    }
    @Test void zeroPhasesExcludeScheduleAndCostButNotOverdueHigh() throws Exception {
        when(mapper.phases(anyLong())).thenReturn(List.of());
        mvc.perform(get("/api/project-board")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].currentPhase").isEmpty())
            .andExpect(jsonPath("$.data.items[0].light").value("정상"));
        var p = source(1, "TK", "0", null);
        when(mapper.projects(any())).thenReturn(List.of(new BoardSource(p.prj_id(), p.prj_no(), p.prj_nm(), null, "TK", 1L,
            p.pmName(), p.cur_cntrct_amt(), null, null, p.actl_prgs_rt(), 1, 1, 1, 1, p.trgt_cst_amt())));
        mvc.perform(get("/api/project-board")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].light").value("위험"));
    }
    @Test void nullDatesRemainPresentAndContributeNoPlan() throws Exception {
        when(mapper.phases(anyLong())).thenReturn(List.of(phase(1, "100", null, null, null)));
        mvc.perform(get("/api/project-board")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].plan_prgs_rt").value(0.0))
            .andExpect(jsonPath("$.data.items[0].currentPhase.plan_bgn_dt").isEmpty())
            .andExpect(jsonPath("$.data.items[0].currentPhase.status").value("예정"));
    }
    @ParameterizedTest @ValueSource(strings = {"MO", "DP", "IN"})
    void onlyTurnkeyExposesCosts(String type) throws Exception {
        when(mapper.projects(any())).thenReturn(List.of(source(1, type, "42.5", TODAY)));
        mvc.perform(get("/api/project-board")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].cost_use_rt").isEmpty())
            .andExpect(jsonPath("$.data.items[0].budget_amt").isEmpty());
        verify(mapper, never()).costs(any());
    }
    @Test void zeroBudgetHidesAllCostFields() throws Exception {
        var p=source(1,"TK","42",TODAY);
        when(mapper.projects(any())).thenReturn(List.of(new BoardSource(p.prj_id(),p.prj_no(),p.prj_nm(),p.custName(),
            p.cntrct_ty_cd(),p.bsn_part_id(),p.pmName(),p.cur_cntrct_amt(),p.prfm_bgn_dt(),p.last_memo_dt(),p.actl_prgs_rt(),
            p.open_issue_count(),p.high_open_issue_count(),p.overdue_issue_count(),p.high_overdue_count(),0L)));
        mvc.perform(get("/api/project-board")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].cost_use_rt").isEmpty())
            .andExpect(jsonPath("$.data.items[0].cumulative_cost_amt").isEmpty());
    }
    @Test void fixedNoticeDoesNotFollowMutableStaleThreshold() throws Exception {
        var rows = new ArrayList<>(params()); rows.set(4, new OperatingParam("SIG_STALE_DAYS", "2026-01", "업데이트", n("30"), null, "일", null));
        when(mapper.params(anyString())).thenReturn(rows);
        when(mapper.projects(any())).thenReturn(List.of(source(1, "MO", null, TODAY.minusDays(15))));
        mvc.perform(get("/api/project-board")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].light").value("정상"))
            .andExpect(jsonPath("$.data.summary.stale_project_count").value(1));
    }
    @Test void missingDatesAreExcludedAndFutureElapsedClamped() throws Exception {
        when(mapper.projects(any())).thenReturn(List.of(source(1, "MO", null, null), source(2, "MO", null, TODAY.plusDays(1))));
        mvc.perform(get("/api/project-board")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].memo_elapsed_days").isEmpty())
            .andExpect(jsonPath("$.data.items[1].memo_elapsed_days").value(0))
            .andExpect(jsonPath("$.data.stale_projects").isEmpty());
    }
}
