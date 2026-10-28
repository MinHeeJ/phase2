package com.sicost.us2;

import static com.sicost.changes.AssignmentChangeModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.changes.*;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
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

@WebMvcTest(controllers = AssignmentChangeController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, AssignmentChangeService.class,
    AssignmentConcurrencyService.class, CurrentUser.class, PermissionService.class, WithdrawalChanceApiTest.Time.class})
class WithdrawalChanceApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AssignmentChangeMapper mapper;
    @MockBean AssignmentConcurrencyMapper concurrency;
    static final LocalDateTime VERSION = LocalDateTime.parse("2026-10-13T12:00:00.123456");
    static final String PATH = "/api/assignments/1/changes";
    State state;
    @BeforeEach void setup() {
        state = state("ACTIVE", "RUN", "Y", "2026-09-01", "M");
        when(mapper.state(1)).thenAnswer(i -> state);
        when(concurrency.lockProject(2)).thenReturn(2L);
        when(concurrency.lockAssignment(1)).thenReturn(new AssignmentConcurrencyMapper.AssignmentLock(1, VERSION, VERSION.plusDays(1)));
        when(concurrency.advance(any())).thenReturn(VERSION.plusDays(1));
        when(mapper.closedMonths()).thenReturn(List.of("2026-09"));
        when(mapper.codeName(any())).thenAnswer(i -> {
            var key = i.getArgument(0, CodeKey.class);
            return "EXT_POSSIBILITY".equals(key.group()) ? Map.of("H", "높음", "M", "보통", "L", "낮음").get(key.code()) : null;
        });
        when(mapper.header(any())).thenAnswer(i -> new Header(1,"A-0001",2,"프로젝트",3,"개발자","K-0003","BE",
            state.bgn_dt(),state.end_dt(),state.end_dt(),BigDecimal.ONE,"NEW","MONTH",state.ext_psblty_cd(),
            null,null,state.asgn_stts_cd(),null,7000000L,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,VERSION.plusDays(1)));
        doAnswer(i -> {
            var write = i.getArgument(0, ChanceWrite.class);
            state = state(state.asgn_stts_cd(),state.prj_stts_cd(),state.use_yn(),state.bgn_dt().toString(),write.value());
            return null;
        }).when(mapper).chance(any());
        when(mapper.insert(any())).thenReturn(7L);
        when(mapper.row(any())).thenAnswer(i -> new ChangeRow(7,1,"EXT_CHANCE",LocalDate.parse("2026-10-14"),
            "보통",state.ext_psblty_cd()==null?"미정":"높음",null,null,null,null,"N","N",null,null,null,null,null,VERSION.plusDays(1)));
    }
    State state(String status, String project, String use, String start, String chance) {
        return new State(1,2,3,LocalDate.parse(start),LocalDate.parse("2026-12-31"),BigDecimal.ONE,
            "NEW",null,null,chance,status,"MO",use,project,"FT",null);
    }
    Map<String,Object> input() {
        var body = new LinkedHashMap<String,Object>();
        body.put("asgn_chg_ty_cd","EXT_CHANCE"); body.put("aply_dt","2026-10-14");
        body.put("updated_at",VERSION.toString()); body.put("ext_psblty_cd","H"); return body;
    }
    ResultActions send(Map<String,Object> body) throws Exception {
        return mvc.perform(post(PATH).contentType("application/json").content(json.writeValueAsBytes(body)));
    }
    void unchanged() {
        verify(mapper,never()).chance(any()); verify(mapper,never()).insert(any());
    }
    @Test void anonymousNullToHighWritesOneNamedHistoryAndReturnsNewVersion() throws Exception {
        state = state("ACTIVE","RUN","Y","2026-09-01",null);
        send(input()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.assignment.ext_psblty_cd").value("H"))
            .andExpect(jsonPath("$.data.assignment.updated_at").value("2026-10-14T12:00:00.123456"))
            .andExpect(jsonPath("$.data.change.asgn_chg_ty_cd").value("EXT_CHANCE"));
        verify(mapper).chance(new ChanceWrite(1,"H"));
        verify(mapper).insert(argThat(w -> w.type().equals("EXT_CHANCE") && w.before().equals("미정")
            && w.after().equals("높음") && w.application().equals(LocalDate.parse("2026-10-14"))
            && w.reason()==null && w.memo()==null && w.retro().equals("N")));
        verify(concurrency).advance(argThat(w -> w.expected().equals(VERSION)));
        verify(mapper,never()).rate(any()); verify(mapper,never()).month(any()); verify(mapper,never()).period(any());
    }
    @Test void clearIsExplicitNullAndHistoryUsesDisplayName() throws Exception {
        var body=input(); body.put("ext_psblty_cd",null);
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.data.assignment.ext_psblty_cd").doesNotExist());
        verify(mapper).chance(new ChanceWrite(1,null));
        verify(mapper).insert(argThat(w -> w.before().equals("보통") && w.after().equals("미정")));
    }
    @Test void equalValueIsBadRequestWithNoHistory() throws Exception {
        var body=input(); body.put("ext_psblty_cd","M");
        send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ext_psblty_cd"))
            .andExpect(jsonPath("$.message").value("현재 값과 같습니다.")); unchanged();
    }
    @Test void omittedChanceDoesNotMeanNull() throws Exception {
        var body=input(); body.remove("ext_psblty_cd");
        send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ext_psblty_cd")); unchanged();
    }
    @Test void invalidChanceRejected() throws Exception {
        var body=input(); body.put("ext_psblty_cd","X");
        send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ext_psblty_cd")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"type","updatedAt","id","rates","months","salary"})
    void unrelatedFieldsRejectedRatherThanAcceptedAsAliases(String field) throws Exception {
        var body=input(); body.put(field,"unexpected");
        send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); unchanged();
    }
    @Test void missingVersionRejected() throws Exception {
        var body=input(); body.remove("updated_at");
        send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("updated_at")); unchanged();
    }
    @Test void missingApplicationDateRejected() throws Exception {
        var body=input(); body.remove("aply_dt");
        send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_dt")); unchanged();
    }
    @Test void futureStartDoesNotClampTodayToStart() throws Exception {
        state=state("ACTIVE","RUN","Y","2026-11-01","M");
        send(input()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_dt"))
            .andExpect(jsonPath("$.message").value("투입일(2026-11-01)보다 앞선 날짜는 넣을 수 없습니다.")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"END","LOST"})
    void closedProjectRetainsItsOwnConflict(String project) throws Exception {
        state=state("ACTIVE",project,"Y","2026-09-01","M");
        send(input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED")); unchanged();
    }
    @Test void unusedProjectIsLocked() throws Exception {
        state=state("ACTIVE","RUN","N","2026-09-01","M");
        send(input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"REPLACED","CANCELLED","ENDED"})
    void lockedAssignmentHasNoBusinessWrites(String status) throws Exception {
        state=state(status,"RUN","Y","2026-09-01","M");
        send(input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSIGNMENT_LOCKED")); unchanged();
    }
    @Test void staleTokenRejectedBeforeChanceOrHistoryWrite() throws Exception {
        var body=input(); body.put("updated_at",VERSION.minusNanos(1000).toString());
        send(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ASSIGNMENT"))
            .andExpect(jsonPath("$.field").value("updated_at")); unchanged();
        verify(concurrency,never()).advance(any());
    }
    @Test void unknownAssignmentIsNotFound() throws Exception {
        mvc.perform(post("/api/assignments/99/changes").contentType("application/json").content(json.writeValueAsBytes(input())))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")); unchanged();
    }
}
