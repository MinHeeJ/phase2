package com.sicost.projectsops;

import static com.sicost.projectsops.ProjectProgressModels.*;
import static com.sicost.apiops.ProjectBoardModels.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.apiops.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=ProjectProgressController.class)
@Import({ProjectProgressService.class, ProgressParameterService.class, ProjectCostService.class,
    PermissionService.class, ApiExceptionHandler.class, RequestIdFilter.class, ProjectProgressApiTest.Time.class})
class ProjectProgressApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @MockBean ProjectProgressMapper mapper;
    @MockBean ProjectBoardMapper board;
    @MockBean ProjectCostService costs;
    final LocalDate today=LocalDate.of(2026,10,23);
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static List<OperatingParam> params() {
        return List.of(new OperatingParam("SIG_DELAY_WARN","2000-01","주의",n("5"),null,null,null),
            new OperatingParam("SIG_DELAY_RISK","2000-01","위험",n("10"),null,null,null),
            new OperatingParam("SIG_COST_WARN","2000-01","주의",n("5"),null,null,null),
            new OperatingParam("SIG_COST_RISK","2000-01","위험",n("15"),null,null,null),
            new OperatingParam("SIG_STALE_DAYS","2000-01","경과",n("14"),null,null,null),
            new OperatingParam("BUDGET_RATE","2000-01","예산",n("80"),null,null,null));
    }
    void project(String type,String status,String enabled,LocalDate start,LocalDate memo, String actual) {
        when(mapper.state(1)).thenReturn(new ProjectState(1,type,status,enabled));
        when(mapper.source(any())).thenReturn(new BoardSource(1,"P-2601","시험",null,type,null,"PM 미지정",500000000L,
            start,memo,actual==null?null:n(actual),0,0,0,0,400000000L));
    }
    Phase phase(int seq,String weight,LocalDate start,LocalDate end,LocalDate completed) {
        return new Phase(seq,1,seq,"단계"+seq,n(weight),start,end,completed,null);
    }
    @BeforeEach void setup() {
        project("TK","RUN","Y",null,today,"42");
        when(board.params("2026-10")).thenReturn(params());
        when(board.phases(1)).thenReturn(List.of(phase(1,"40",today.minusDays(80),today.minusDays(31),today.minusDays(31)),
            phase(2,"35",today.minusDays(30),today.plusDays(69),null),phase(3,"25",today.plusDays(70),today.plusDays(80),null)));
        when(costs.calculate(anyLong(), anyString())).thenReturn(new ProjectCost(0, List.of()));
        when(mapper.memos(1)).thenReturn(List.of(new MemoSource(1,1,today,n("42"),"한 일","할 일",null)));
        when(mapper.issues(1)).thenReturn(List.of());
    }
    @Test void anonymousProgressCalculatesFiftyPointFiveAndWarn() throws Exception {
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data.plan_prgs_rt").value(50.5)).andExpect(jsonPath("$.data.actl_prgs_rt").value(42))
            .andExpect(jsonPath("$.data.schedule_diff_rt").value(-8.5)).andExpect(jsonPath("$.data.light").value("주의"))
            .andExpect(jsonPath("$.data.reasons[0]").value("일정이 계획보다 8.5%p 늦음"))
            .andExpect(jsonPath("$.data.pmName").value("PM 미지정")).andExpect(jsonPath("$.data.currentPhase.phs_id").value(2));
        verify(mapper).source(new ProgressRead(1,today));
    }
    @Test void costRiskOverridesScheduleWarn() throws Exception {
        when(costs.calculate(anyLong(), anyString())).thenReturn(new ProjectCost(230000000, List.of()));
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.budget_amt").value(400000000)).andExpect(jsonPath("$.data.cost_use_rt").value(57.5))
            .andExpect(jsonPath("$.data.cost_progress_diff_rt").value(15.5)).andExpect(jsonPath("$.data.light").value("위험"))
            .andExpect(jsonPath("$.data.reasons[1]").value("원가 사용률이 진척률보다 15.5%p 높음 — 원가 초과 위험"));
    }
    @ParameterizedTest @ValueSource(strings={"MO","DP"}) void operatingUsesPlanAndHidesCost(String type) throws Exception {
        project(type,"RUN","Y",null,today,"99.5");
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.actl_prgs_rt").value(50.5)).andExpect(jsonPath("$.data.schedule_diff_rt").value(0))
            .andExpect(jsonPath("$.data.cost_use_rt").isEmpty()).andExpect(jsonPath("$.data.memos[0].actl_prgs_rt").isEmpty());
        verify(board,never()).costs(any());
    }
    @Test void missingMemoManualActualIsZero() throws Exception {
        project("IN","RUN","Y",null,null,null); when(mapper.memos(1)).thenReturn(List.of());
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk()).andExpect(jsonPath("$.data.actl_prgs_rt").value(0));
    }
    @ParameterizedTest @ValueSource(strings={"42.5","101","-1"}) void legacyFractionIsRejectedWithoutMutation(String rate) throws Exception {
        when(mapper.memos(1)).thenReturn(List.of(new MemoSource(1,1,today,n(rate),null,null,null)));
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.field").value("actl_prgs_rt"));
        verify(board,never()).costs(any());
    }
    @Test void zeroPhasesExcludesCostAndScheduleFromLight() throws Exception {
        when(board.phases(1)).thenReturn(List.of());
        when(costs.calculate(anyLong(), anyString())).thenReturn(new ProjectCost(900000000, List.of()));
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plan_prgs_rt").value(0)).andExpect(jsonPath("$.data.currentPhase").isEmpty())
            .andExpect(jsonPath("$.data.light").value("정상"));
    }
    @Test void nullDatesPreservedAndCompletedTakesPriority() throws Exception {
        when(board.phases(1)).thenReturn(List.of(phase(1,"100",null,null,today)));
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk()).andExpect(jsonPath("$.data.plan_prgs_rt").value(0))
            .andExpect(jsonPath("$.data.phases[0].plan_bgn_dt").isEmpty()).andExpect(jsonPath("$.data.phases[0].status").value("완료"))
            .andExpect(jsonPath("$.data.currentPhase.phs_id").value(1));
    }
    @Test void startDayElapsedIsZeroAndHundredDayThirtyUsesThirtyPercent() throws Exception {
        project("MO","RUN","Y",today,null,null);
        when(board.phases(1)).thenReturn(List.of(phase(1,"100",today,today.plusDays(99),null)));
        mvc.perform(get("/api/projects/1/progress")).andExpect(jsonPath("$.data.plan_prgs_rt").value(0))
            .andExpect(jsonPath("$.data.memo_elapsed_days").value(0));
        when(board.phases(1)).thenReturn(List.of(phase(1,"100",today.minusDays(30),today.plusDays(69),null)));
        mvc.perform(get("/api/projects/1/progress")).andExpect(jsonPath("$.data.plan_prgs_rt").value(30));
    }
    @ParameterizedTest @ValueSource(ints={0,14,15}) void staleBoundary(int days) throws Exception {
        project("MO","RUN","Y",null,today.minusDays(days),null);
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memo_elapsed_days").value(days)).andExpect(jsonPath("$.data.light").value(days>14?"주의":"정상"));
    }
    @Test void noMemoOrStartExcludesStaleAndFutureClampsZero() throws Exception {
        project("MO","RUN","Y",null,null,null);
        mvc.perform(get("/api/projects/1/progress")).andExpect(jsonPath("$.data.memo_elapsed_days").isEmpty())
            .andExpect(jsonPath("$.data.last_memo_dt").isEmpty()).andExpect(jsonPath("$.data.light").value("정상"));
        project("MO","RUN","Y",today.plusDays(1),null,null);
        mvc.perform(get("/api/projects/1/progress")).andExpect(jsonPath("$.data.memo_elapsed_days").value(0));
    }
    @ParameterizedTest @ValueSource(strings={"END","LOST"}) void closedStillReadableWithoutLight(String status) throws Exception {
        project("TK",status,"Y",null,today,"42");
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk()).andExpect(jsonPath("$.data.light").isEmpty())
            .andExpect(jsonPath("$.data.memos[0].actl_prgs_rt").value(42));
    }
    @Test void disabledStillReadableWithoutLight() throws Exception {
        project("TK","RUN","N",null,today,"42");
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isOk()).andExpect(jsonPath("$.data.light").isEmpty());
    }
    @Test void missingParametersFailInsteadOfGreen() throws Exception {
        when(board.params(anyString())).thenReturn(List.of());
        mvc.perform(get("/api/projects/1/progress")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));
    }
    @ParameterizedTest @ValueSource(strings={"progress","issues"}) void missingProject(String suffix) throws Exception {
        mvc.perform(get("/api/projects/2/"+suffix)).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"progress","issues"}) void invalidId(String suffix) throws Exception {
        mvc.perform(get("/api/projects/0/"+suffix)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
    }
    @Test void issueListIncludesClosedButCountsOnlyOpenAndDoing() throws Exception {
        when(mapper.issues(1)).thenReturn(List.of(new ProjectIssue(1,"열림","HIGH",null,today,"OPEN",null,1,today,null),
            new ProjectIssue(1,"종료","HIGH",null,today.minusDays(1),"CLOSED","조치",2,today,today)));
        mvc.perform(get("/api/projects/1/issues")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.open_count").value(1)).andExpect(jsonPath("$.data.items[1].reg_dt").value("2026-10-23"));
    }
    @Test void emptyIssues() throws Exception {
        mvc.perform(get("/api/projects/1/issues")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.open_count").value(0));
    }
}
