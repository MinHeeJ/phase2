package com.sicost.partchanges;

import static com.sicost.partchanges.PartChangeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
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

@WebMvcTest(controllers = PartChangeController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, PartChangeService.class,
    CurrentUser.class, PermissionService.class, PartChangeApiTest.Time.class})
class PartChangeApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartChangeMapper mapper;
    PartChangeProject project;
    final TreeMap<String, PartChange> rows = new TreeMap<>();
    final List<PartChangeAudit> audits = new ArrayList<>();
    final LocalDateTime changedAt = LocalDateTime.of(2026, 10, 23, 0, 1);

    @BeforeEach void setup() {
        project = new PartChangeProject(1, "MO", "Y", 11L);
        rows.clear(); audits.clear();
        rows.put("2026-03", new PartChange(11L, "2026-03", null, null, changedAt, "A파트"));
        when(mapper.project(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? project : null);
        when(mapper.list(1)).thenAnswer(i -> List.copyOf(rows.values()));
        when(mapper.lastClosedMonth()).thenReturn("2026-09");
        when(mapper.activePartName(11)).thenReturn("A파트");
        when(mapper.activePartName(12)).thenReturn("B파트");
        when(mapper.activePartName(13)).thenReturn("본사");
        doAnswer(i -> {
            var w = i.getArgument(0, PartChangeWrite.class);
            var r = w.input();
            rows.put(r.aply_bgn_ym(), new PartChange(r.bsn_part_id(), r.aply_bgn_ym(), r.rsn_desc(),
                w.actor(), changedAt, mapper.activePartName(r.bsn_part_id())));
            return null;
        }).when(mapper).upsert(any());
        doAnswer(i -> {
            project = new PartChangeProject(1, project.cntrct_ty_cd(), project.use_yn(), rows.lastEntry().getValue().bsn_part_id());
            return null;
        }).when(mapper).updateRepresentative(any());
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    ResultActions save(long id, Long part, String month, String reason) throws Exception {
        return mvc.perform(post("/api/projects/" + id + "/part-changes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new PartChangeRequest(part, month, reason))));
    }
    void untouched() {
        verify(mapper, never()).upsert(any());
        verify(mapper, never()).updateRepresentative(any());
        verify(mapper, never()).audit(any());
        assertThat(project.bsn_part_id()).isEqualTo(11);
        assertThat(rows).hasSize(1);
    }
    void bad(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        untouched();
    }
    @Test void anonymousGetReadsWholeOrderedArrayWithoutWriting() throws Exception {
        mvc.perform(get("/api/projects/1/part-changes")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].partName").value("A파트"))
            .andExpect(jsonPath("$.data[0].aply_bgn_ym").value("2026-03"))
            .andExpect(jsonPath("$.data[0].chg_at").value("2026-10-23T00:01:00"));
        untouched();
    }
    @Test void anonymousPostUpsertsRecalculatesAndAppendsOneSummary() throws Exception {
        mvc.perform(post("/api/projects/1/part-changes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new PartChangeRequest(12L, "2026-10", "  담당 변경  "))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[1].rsn_desc").value("담당 변경"));
        mvc.perform(get("/api/projects/1/part-changes")).andExpect(jsonPath("$.data[1].bsn_part_id").value(12));
        assertThat(project.bsn_part_id()).isEqualTo(12);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).id()).isEqualTo("1");
        assertThat(audits.get(0).actor()).isNull();
        assertThat(audits.get(0).after()).contains("B파트", "2026-10", "담당 변경");
        var order = inOrder(mapper);
        order.verify(mapper).upsert(new PartChangeWrite(1, new PartChangeRequest(12L, "2026-10", "담당 변경"), null));
        order.verify(mapper).updateRepresentative(any());
        order.verify(mapper).audit(any());
    }
    @Test void sameMonthCorrectionKeepsCountAndRecordsOldAndNewReason() throws Exception {
        save(1, 12L, "2026-10", "첫 변경").andExpect(status().isOk());
        save(1, 13L, "2026-10", "정정").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        assertThat(project.bsn_part_id()).isEqualTo(13);
        assertThat(audits.get(1).before()).contains("B파트", "2026-10", "첫 변경");
        assertThat(audits.get(1).after()).contains("본사", "2026-10", "정정");
    }
    @Test void openPastMonthAllowedAndFutureMonthRemainsRepresentative() throws Exception {
        save(1, 12L, "2027-05", "향후 담당").andExpect(status().isOk());
        save(1, 13L, "2026-10", "미마감 과거월").andExpect(status().isOk());
        assertThat(project.bsn_part_id()).isEqualTo(12);
        assertThat(rows.keySet()).containsExactly("2026-03", "2026-10", "2027-05");
    }
    @Test void noClosedMonthAllowsAnyValidMonth() throws Exception {
        when(mapper.lastClosedMonth()).thenReturn(null);
        save(1, 13L, "2025-02", "본사 담당").andExpect(status().isOk());
        assertThat(project.bsn_part_id()).isEqualTo(11);
    }
    @ParameterizedTest @ValueSource(strings = {"2026-09", "2026-08", "2025-12"})
    void closedAndEarlierMonthConflictPreservesEverything(String month) throws Exception {
        save(1, 12L, month, "변경").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"))
            .andExpect(jsonPath("$.field").value("aply_bgn_ym"))
            .andExpect(jsonPath("$.message").value("적용 시작 월은 2026-09 이후로 골라 주세요."));
        untouched();
    }
    @Test void blankReasonHasExactMessage() throws Exception {
        bad(save(1, 12L, "2026-10", "   ").andExpect(jsonPath("$.message").value("사유를 입력해 주세요.")), "rsn_desc");
    }
    @Test void missingReasonRejected() throws Exception { bad(save(1, 12L, "2026-10", null), "rsn_desc"); }
    @Test void longReasonRejected() throws Exception { bad(save(1, 12L, "2026-10", "가".repeat(201)), "rsn_desc"); }
    @Test void exactReasonLimitAllowed() throws Exception { save(1, 12L, "2026-10", "가".repeat(200)).andExpect(status().isOk()); }
    @Test void internalProjectCannotChangePart() throws Exception {
        project = new PartChangeProject(1, "IN", "Y", 11L);
        bad(save(1, 12L, "2026-10", "변경"), "cntrct_ty_cd");
    }
    @Test void disabledProjectCannotMutate() throws Exception {
        project = new PartChangeProject(1, "MO", "N", 11L);
        bad(save(1, 12L, "2026-10", "변경"), "use_yn");
    }
    @Test void disabledProjectCanReadHistory() throws Exception {
        project = new PartChangeProject(1, "MO", "N", 11L);
        mvc.perform(get("/api/projects/1/part-changes")).andExpect(status().isOk()); untouched();
    }
    @Test void samePartRejected() throws Exception { bad(save(1, 11L, "2026-10", "변경"), "bsn_part_id"); }
    @Test void inactivePartRejected() throws Exception { bad(save(1, 99L, "2026-10", "변경"), "bsn_part_id"); }
    @Test void missingPartRejected() throws Exception { bad(save(1, null, "2026-10", "변경"), "bsn_part_id"); }
    @Test void zeroPartRejected() throws Exception { bad(save(1, 0L, "2026-10", "변경"), "bsn_part_id"); }
    @ParameterizedTest @ValueSource(strings = {"2026-13", "2026-1", "0000-01", "", "2026-10-01"})
    void malformedMonthsRejected(String month) throws Exception { bad(save(1, 12L, month, "변경"), "aply_bgn_ym"); }
    @Test void nullMonthRejected() throws Exception { bad(save(1, 12L, null, "변경"), "aply_bgn_ym"); }
    @ParameterizedTest @ValueSource(strings = {"GET", "POST"})
    void invalidIdRejected(String method) throws Exception {
        bad(method.equals("GET") ? mvc.perform(get("/api/projects/0/part-changes")) : save(0, 12L, "2026-10", "변경"), "id");
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "POST"})
    void absentProjectIs404(String method) throws Exception {
        (method.equals("GET") ? mvc.perform(get("/api/projects/99/part-changes")) : save(99, 12L, "2026-10", "변경"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"\"12\"", "12.5", "9223372036854775808", "true"})
    void strictPartNumberParsing(String part) throws Exception {
        bad(mvc.perform(post("/api/projects/1/part-changes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"bsn_part_id\":" + part + ",\"aply_bgn_ym\":\"2026-10\",\"rsn_desc\":\"변경\"}")), "bsn_part_id");
    }
    @Test void responseFieldsCannotBeSubmitted() throws Exception {
        bad(mvc.perform(post("/api/projects/1/part-changes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"bsn_part_id\":12,\"aply_bgn_ym\":\"2026-10\",\"rsn_desc\":\"변경\",\"chg_user_id\":1}")), "chg_user_id");
    }
    @ParameterizedTest @ValueSource(strings = {"[]", "{} {}", "null", "{broken"})
    void malformedJsonDoesNotWrite(String body) throws Exception {
        mvc.perform(post("/api/projects/1/part-changes").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()); untouched();
    }
}
