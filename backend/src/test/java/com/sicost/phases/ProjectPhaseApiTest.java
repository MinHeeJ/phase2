package com.sicost.phases;

import static com.sicost.phases.ProjectPhaseModels.*;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = ProjectPhaseController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectPhaseService.class,
    CurrentUser.class, PermissionService.class, ProjectPhaseApiTest.Time.class})
class ProjectPhaseApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneOffset.UTC); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectPhaseMapper mapper;
    PhaseProject project;
    Phase phase;
    final List<PhaseAudit> history = new ArrayList<>();
    static final LocalDate TODAY = LocalDate.of(2026,10,23);
    static final String BASE = "/api/projects/1/phases/7";
    @BeforeEach void setup() {
        project = new PhaseProject("Y", "RUN");
        phase = phase(LocalDate.of(2026,10,1), LocalDate.of(2026,10,31), null);
        history.clear();
        when(mapper.lockProject(anyLong())).thenAnswer(i -> i.getArgument(0,Long.class) == 1 ? project : null);
        when(mapper.row(any())).thenAnswer(i -> {
            var k = i.getArgument(0,PhaseKey.class); return k.id() == 1 && k.phsId() == 7 ? phase : null;
        });
        when(mapper.complete(any())).thenAnswer(i -> {
            if (phase.cmpl_dt() != null) return 0;
            phase = phase(phase.plan_bgn_dt(), phase.plan_end_dt(), i.getArgument(0,PhaseWrite.class).key().today());
            return 1;
        });
        when(mapper.uncomplete(any())).thenAnswer(i -> { phase = phase(phase.plan_bgn_dt(), phase.plan_end_dt(), null); return 1; });
        when(mapper.period(any())).thenAnswer(i -> {
            var p = i.getArgument(0,PhaseWrite.class).period(); phase = phase(p.plan_bgn_dt(),p.plan_end_dt(),phase.cmpl_dt()); return 1;
        });
        doAnswer(i -> { history.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    Phase phase(LocalDate start, LocalDate end, LocalDate done) {
        String status = done != null ? "완료" : start == null || end == null ? "예정" : end.isBefore(TODAY) ? "지연" : start.isAfter(TODAY) ? "예정" : "진행 중";
        return new Phase(7,1,1,"개발",new BigDecimal("35.25"),start,end,done,status);
    }
    ResultActions period(String start, String end) throws Exception {
        return mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("plan_bgn_dt",start,"plan_end_dt",end))));
    }
    void unchanged(Phase before) {
        assertThat(phase).isEqualTo(before); assertThat(history).isEmpty();
        verify(mapper,never()).complete(any()); verify(mapper,never()).uncomplete(any()); verify(mapper,never()).period(any());
    }
    @Test void anonymousCompleteUsesSeoulDateAndChildColumnAudit() throws Exception {
        mvc.perform(post(BASE+"/complete")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.phs_id").value(7))
            .andExpect(jsonPath("$.data.cmpl_dt").value("2026-10-23")).andExpect(jsonPath("$.data.status").value("완료"))
            .andExpect(jsonPath("$.data.wght_rt").value(35.25));
        assertThat(history).containsExactly(new PhaseAudit("7","cmpl_dt",null,"2026-10-23",null));
    }
    @Test void anonymousDelayedPhaseCompletionOverridesDelay() throws Exception {
        phase = phase(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),null);
        mvc.perform(post(BASE+"/complete")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("완료"));
    }
    @Test void startDateTodayIsAllowed() throws Exception {
        phase = phase(TODAY,TODAY,null);
        mvc.perform(post(BASE+"/complete")).andExpect(status().isOk());
    }
    @Test void duplicateCompleteReturnsSpecifiedConflictWithoutExtraHistory() throws Exception {
        phase = phase(phase.plan_bgn_dt(),phase.plan_end_dt(),TODAY);
        mvc.perform(post(BASE+"/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_DONE"))
            .andExpect(jsonPath("$.message").value("이미 완료된 단계입니다."));
        assertThat(history).isEmpty(); assertThat(phase.cmpl_dt()).isEqualTo(TODAY);
    }
    @Test void conditionalUpdateZeroIsAlreadyDone() throws Exception {
        doReturn(0).when(mapper).complete(any());
        mvc.perform(post(BASE+"/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_DONE"));
        assertThat(history).isEmpty();
    }
    @Test void futureStartIsRejectedWithoutMutation() throws Exception {
        phase = phase(TODAY.plusDays(1),TODAY.plusDays(2),null); var before = phase;
        mvc.perform(post(BASE+"/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHASE_NOT_STARTED"));
        unchanged(before);
    }
    @Test void missingStartIsRejectedAndNotInvented() throws Exception {
        phase = phase(null,null,null); var before = phase;
        mvc.perform(post(BASE+"/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHASE_NOT_STARTED"));
        unchanged(before);
    }
    @Test void anonymousUncompleteRecalculatesStatusAndAuditsOldDateToNull() throws Exception {
        phase = phase(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),TODAY.minusDays(1));
        mvc.perform(post("/api/projects/1/phases/7/uncomplete")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cmpl_dt").isEmpty())
            .andExpect(jsonPath("$.data.status").value("지연"));
        assertThat(history).containsExactly(new PhaseAudit("7","cmpl_dt","2026-10-22",null,null));
    }
    @Test void repeatedUncompleteIsNoOpWithoutAudit() throws Exception {
        var before = phase;
        mvc.perform(post(BASE+"/uncomplete")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cmpl_dt").isEmpty());
        unchanged(before);
    }
    @Test void anonymousPeriodChangesOnlyDatesAndAuditsBothChangedColumns() throws Exception {
        period("2026-09-01","2026-12-31").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plan_bgn_dt").value("2026-09-01")).andExpect(jsonPath("$.data.plan_end_dt").value("2026-12-31"))
            .andExpect(jsonPath("$.data.phs_nm").value("개발")).andExpect(jsonPath("$.data.wght_rt").value(35.25));
        assertThat(history).containsExactly(new PhaseAudit("7","plan_bgn_dt","2026-10-01","2026-09-01",null),
            new PhaseAudit("7","plan_end_dt","2026-10-31","2026-12-31",null));
    }
    @Test void oneChangedDateProducesOnlyOneHistory() throws Exception {
        period("2026-10-01","2026-12-31").andExpect(status().isOk());
        assertThat(history).hasSize(1); assertThat(history.get(0).column()).isEqualTo("plan_end_dt");
    }
    @Test void identicalPeriodIsNoOp() throws Exception {
        var before = phase; period("2026-10-01","2026-10-31").andExpect(status().isOk()); unchanged(before);
    }
    @Test void sameDayPeriodIsValid() throws Exception { period("2026-10-23","2026-10-23").andExpect(status().isOk()); }
    @Test void legacyNullDatesCanBeUpdated() throws Exception {
        phase = phase(null,null,null); period("2026-10-23","2026-10-31").andExpect(status().isOk());
        assertThat(history).hasSize(2); assertThat(history).allMatch(h -> h.before() == null);
    }
    @Test void reversedPeriodReturnsFieldAndExactMessageWithoutMutation() throws Exception {
        var before = phase;
        period("2026-11-01","2026-10-31").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("plan_end_dt"))
            .andExpect(jsonPath("$.message").value("예정 종료일은 시작일과 같거나 뒤여야 합니다.")); unchanged(before);
    }
    @Test void completedPeriodIsRejectedWithoutMutation() throws Exception {
        phase = phase(phase.plan_bgn_dt(),phase.plan_end_dt(),TODAY); var before = phase;
        period("2026-09-01","2026-12-31").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHASE_DONE")); unchanged(before);
    }
    @ParameterizedTest @ValueSource(strings={"complete","uncomplete","period"})
    void wrongProjectChildIsNotFoundBeforeClosedConflict(String operation) throws Exception {
        project = new PhaseProject("Y","END"); var before = phase;
        var request = operation.equals("period") ? put("/api/projects/1/phases/8").contentType(MediaType.APPLICATION_JSON)
            .content("{\"plan_bgn_dt\":\"2026-10-01\",\"plan_end_dt\":\"2026-12-31\"}") : post("/api/projects/1/phases/8/"+operation);
        mvc.perform(request).andExpect(status().isNotFound()); unchanged(before);
    }
    @ParameterizedTest @ValueSource(strings={"complete","uncomplete","period"})
    void missingProjectIsNotFound(String operation) throws Exception {
        var before = phase;
        var request = operation.equals("period") ? put("/api/projects/2/phases/7").contentType(MediaType.APPLICATION_JSON)
            .content("{\"plan_bgn_dt\":\"2026-10-01\",\"plan_end_dt\":\"2026-12-31\"}") : post("/api/projects/2/phases/7/"+operation);
        mvc.perform(request).andExpect(status().isNotFound()); unchanged(before);
    }
    @ParameterizedTest @ValueSource(strings={"END","LOST","N"})
    void closedProjectRejectsEveryWriteIncludingRepeatUncomplete(String state) throws Exception {
        project = new PhaseProject(state.equals("N") ? "N" : "Y",state.equals("N") ? "RUN" : state); var before = phase;
        mvc.perform(post(BASE+"/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        mvc.perform(post(BASE+"/uncomplete")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        period("2026-09-01","2026-12-31").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED")); unchanged(before);
    }
    @ParameterizedTest @ValueSource(strings={"PLAN","RUN","HOLD"})
    void allWritableStatesAllowAnonymousWrites(String state) throws Exception {
        project = new PhaseProject("Y",state);
        period("2026-09-01","2026-12-31").andExpect(status().isOk());
        mvc.perform(post(BASE+"/complete")).andExpect(status().isOk());
        mvc.perform(post(BASE+"/uncomplete")).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"plan_bgn_dt\":\"2026-02-30\",\"plan_end_dt\":\"2026-12-31\"}","{\"plan_bgn_dt\":4,\"plan_end_dt\":\"2026-12-31\"}","{\"phs_nm\":\"다른 이름\"}","{} {}"})
    void invalidPeriodBodyHasNoEffects(String body) throws Exception {
        var before = phase;
        mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.requestId").isNotEmpty()); unchanged(before);
    }
    @Test void invalidIdentifiersReturn400() throws Exception {
        mvc.perform(post("/api/projects/0/phases/7/complete")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
        mvc.perform(post("/api/projects/1/phases/0/uncomplete")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("phsId"));
    }
}
