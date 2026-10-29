package com.sicost.allocations;

import static com.sicost.allocations.ProjectAllocationModels.*;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = ProjectAllocationController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectAllocationService.class,
        CurrentUser.class, PermissionService.class})
class ProjectAllocationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectAllocationMapper mapper;
    final Map<AllocationScope, List<AllocationStoredLine>> rows = new HashMap<>();
    final Map<Long, List<AllocationMonth>> months = new HashMap<>();
    final List<AllocationAudit> audits = new ArrayList<>();
    long sequence;
    AllocationProject project;
    String realoc;

    @BeforeEach void setup() {
        rows.clear(); months.clear(); audits.clear(); sequence = 10; realoc = "Y";
        project = new AllocationProject("TK", "Y", 600000000L,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(mapper.project(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? project : null);
        when(mapper.activeStage(anyLong())).thenAnswer(i -> List.of("ACTUAL", "FINAL", "EST").stream()
                .filter(s -> !rows.getOrDefault(new AllocationScope(1, s), List.of()).isEmpty()).findFirst().orElse(null));
        when(mapper.reallocation(anyLong())).thenAnswer(i -> realoc);
        when(mapper.lines(any())).thenAnswer(i -> rows.getOrDefault(i.getArgument(0), List.of()));
        when(mapper.months(anyLong())).thenAnswer(i -> months.getOrDefault(i.getArgument(0), List.of()));
        when(mapper.activeCode(any())).thenReturn(true);
        when(mapper.activeDeveloper(anyLong())).thenReturn(true);
        doAnswer(i -> { rows.remove(i.getArgument(0)); return null; }).when(mapper).deleteLines(any());
        doAnswer(i -> {
            for (var l : rows.getOrDefault(i.getArgument(0), List.of())) months.remove(l.aloc_line_id());
            return null;
        }).when(mapper).deleteMonths(any());
        when(mapper.insertLine(any())).thenAnswer(i -> {
            var w = i.getArgument(0, AllocationWrite.class); var l = w.line(); long id = ++sequence;
            rows.computeIfAbsent(w.scope(), k -> new ArrayList<>()).add(new AllocationStoredLine(id,
                    l.line_ty_cd(), l.dev_id(), l.item_nm(), l.job_cd(), l.asgn_bgn_dt(),
                    l.asgn_se_cd(), l.rmrk_desc(), l.sort_seq()));
            return id;
        });
        doAnswer(i -> { var w = i.getArgument(0, AllocationMonthWrite.class);
            months.computeIfAbsent(w.id(), k -> new ArrayList<>()).add(w.month()); return null;
        }).when(mapper).insertMonth(any());
        doAnswer(i -> { realoc = "N"; return null; }).when(mapper).clearReallocation(anyLong());
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    String item(long amount) {
        return "{\"items\":[{\"line_ty_cd\":\"ITEM\",\"item_nm\":\"기술료\",\"months\":[{\"aloc_ym\":\"2026-10\",\"aloc_amt\":" + amount + ",\"mm_qty\":1.25}]}]}";
    }
    ResultActions save(String stage, String body) throws Exception {
        return mvc.perform(put("/api/projects/1/allocations").param("stage", stage)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    ResultActions read() throws Exception { return mvc.perform(get("/api/projects/1/allocations")); }
    void untouched() { verify(mapper, never()).deleteLines(any()); verify(mapper, never()).audit(any()); }
    @Test void anonymousGetIsReadOnly() throws Exception {
        mvc.perform(get("/api/projects/1/allocations")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.aloc_stg_cd").value("EST"))
                .andExpect(jsonPath("$.data.activeStage").isEmpty())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.realoc_need_yn").value("Y"));
        untouched();
    }
    @Test void actualSixHundredMillionAnonymousItemReadback() throws Exception {
        mvc.perform(put("/api/projects/1/allocations").param("stage", "ACTUAL")
            .contentType(MediaType.APPLICATION_JSON).content(item(600000000)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.realoc_need_yn").value("N"));
        read().andExpect(jsonPath("$.data.activeStage").value("ACTUAL"))
                .andExpect(jsonPath("$.data.items[0].item_nm").value("기술료"))
                .andExpect(jsonPath("$.data.items[0].dev_id").isEmpty())
                .andExpect(jsonPath("$.data.items[0].months[0].aloc_amt").value(600000000))
                .andExpect(jsonPath("$.data.items[0].months[0].mm_qty").value(1.25));
    }
    @Test void allStagePriorityAndMismatchNeverBlocksSave() throws Exception {
        for (String stage : List.of("EST", "FINAL", "ACTUAL")) save(stage, item(600000000)).andExpect(status().isOk());
        mvc.perform(put("/api/projects/1/allocations").param("stage","ACTUAL")
            .contentType(MediaType.APPLICATION_JSON).content(item(590000000)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.realoc_need_yn").value("N"));
        read().andExpect(jsonPath("$.data.activeStage").value("ACTUAL"))
                .andExpect(jsonPath("$.data.cur_cntrct_amt").value(600000000))
                .andExpect(jsonPath("$.data.items[0].months[0].aloc_amt").value(590000000));
        mvc.perform(get("/api/projects/1/allocations").param("stage", "FINAL"))
                .andExpect(jsonPath("$.data.items[0].months[0].aloc_amt").value(600000000));
    }
    @Test void finalThenEstimateFallbackAfterEmptyReplacement() throws Exception {
        save("EST", item(10)); save("FINAL", item(20));
        read().andExpect(jsonPath("$.data.aloc_stg_cd").value("FINAL"));
        save("FINAL", "{\"items\":[]}").andExpect(status().isOk());
        read().andExpect(jsonPath("$.data.aloc_stg_cd").value("EST"));
    }
    @Test void saveClearsOnlyProjectAndAuditsOneSelectedStage() throws Exception {
        save("EST", item(5)); audits.clear();
        save("ACTUAL", item(600000000)).andExpect(jsonPath("$.data.realoc_need_yn").value("N"));
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).stage()).isEqualTo("ACTUAL");
        assertThat(audits.get(0).actor()).isNull();
        assertThat(audits.get(0).after()).contains("600000000원");
        verify(mapper, times(2)).clearReallocation(1);
        mvc.perform(get("/api/projects/1/allocations").param("stage", "EST"))
                .andExpect(jsonPath("$.data.items[0].months[0].aloc_amt").value(5));
    }
    @Test void missingPersonJobLeavesOldRowsMonthsAndAuditUntouched() throws Exception {
        save("ACTUAL", item(10)); var before = new HashMap<>(months); int count = audits.size();
        clearInvocations(mapper);
        save("ACTUAL", """
            {"items":[{"line_ty_cd":"PERSON","dev_id":2,"asgn_bgn_dt":"2026-10-01","months":[]}]}
            """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("job_cd"));
        assertThat(months).isEqualTo(before); assertThat(audits).hasSize(count); untouched();
    }
    @ParameterizedTest
    @CsvSource({"dev_id,\"dev_id\":null", "asgn_bgn_dt,\"asgn_bgn_dt\":null"})
    void personRequiredFields(String field, String replacement) throws Exception {
        String body = "{\"items\":[{\"line_ty_cd\":\"PERSON\",\"dev_id\":2,\"job_cd\":\"BE\",\"asgn_bgn_dt\":\"2026-10-01\",\"months\":[]}]}";
        var n = json.readTree(body); ((com.fasterxml.jackson.databind.node.ObjectNode)n.get("items").get(0)).putNull(field);
        save("ACTUAL", n.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); untouched();
    }
    @Test void personHappyStoresJobDateAndOptionalKind() throws Exception {
        save("ACTUAL", """
            {"items":[{"line_ty_cd":"PERSON","dev_id":2,"job_cd":"BE","asgn_bgn_dt":"2026-10-01","asgn_se_cd":"NEW","months":[]}]}
            """).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].job_cd").value("BE"))
                .andExpect(jsonPath("$.data.items[0].asgn_bgn_dt").value("2026-10-01"));
    }
    @Test void blankMonthCreatesNoCellButExplicitZeroDoes() throws Exception {
        save("EST", """
            {"items":[{"line_ty_cd":"ITEM","item_nm":"경비","months":[{"aloc_ym":"2026-09"},{"aloc_ym":"2026-10","aloc_amt":0}]}]}
            """).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].months.length()").value(1));
        verify(mapper, times(1)).insertMonth(any());
    }
    @Test void disabledProjectReadsButDoesNotWrite() throws Exception {
        project = new AllocationProject("TK", "N", 600000000L, project.begin_date(), project.end_date());
        read().andExpect(status().isOk());
        save("EST", item(1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("use_yn")); untouched();
    }
    @Test void nonTurnkeyRejected() throws Exception {
        project = new AllocationProject("MO", "Y", 600000000L, project.begin_date(), project.end_date());
        read().andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cntrct_ty_cd"));
        save("EST", item(1)).andExpect(status().isBadRequest()); untouched();
    }
    @Test void missingProject404BothOperations() throws Exception {
        mvc.perform(get("/api/projects/99/allocations")).andExpect(status().isNotFound());
        mvc.perform(put("/api/projects/99/allocations").param("stage", "EST")
                .contentType(MediaType.APPLICATION_JSON).content(item(1))).andExpect(status().isNotFound()); untouched();
    }
    @Test void unknownStageAndMissingStageRejected() throws Exception {
        save("OTHER", item(1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("stage"));
        mvc.perform(put("/api/projects/1/allocations").contentType(MediaType.APPLICATION_JSON).content(item(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("stage")); untouched();
    }
    @Test void invalidCodeRejectedBeforeReplacement() throws Exception {
        when(mapper.activeCode(any())).thenReturn(false);
        save("EST", """
            {"items":[{"line_ty_cd":"ITEM","item_nm":"경비","asgn_se_cd":"WRONG","months":[]}]}
            """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("asgn_se_cd")); untouched();
    }
    @Test void inactiveDeveloperRejectedBeforeReplacement() throws Exception {
        when(mapper.activeDeveloper(anyLong())).thenReturn(false);
        save("EST", """
            {"items":[{"line_ty_cd":"PERSON","dev_id":2,"job_cd":"BE","asgn_bgn_dt":"2026-10-01","months":[]}]}
            """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("dev_id")); untouched();
    }
    @Test void outsidePeriodNewCellRejected() throws Exception {
        save("EST", item(10).replace("2026-10", "2027-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("aloc_ym")); untouched();
    }
    @Test void shrinkPreservesUnchangedOutsideMonthAndAllowsExplicitRemoval() throws Exception {
        save("EST", item(10));
        project = new AllocationProject("TK", "Y", 600000000L, project.begin_date(), LocalDate.of(2026, 9, 30));
        save("EST", item(10)).andExpect(status().isOk());
        read().andExpect(jsonPath("$.data.items[0].months[0].aloc_amt").value(10));
        save("EST", item(10).replace("\"aloc_amt\":10", "\"aloc_amt\":null"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].months[0].mm_qty").value(1.25));
        int count = audits.size(); clearInvocations(mapper);
        save("EST", item(11)).andExpect(status().isBadRequest());
        assertThat(audits).hasSize(count); untouched();
        save("EST", "{\"items\":[]}").andExpect(status().isOk());
    }
    @ParameterizedTest
    @CsvSource({"aloc_amt,9223372036854775808", "aloc_amt,1.5", "mm_qty,1.001", "mm_qty,1000"})
    void numericSafety(String field, String value) throws Exception {
        var body = json.readTree(item(1)); var cell = (com.fasterxml.jackson.databind.node.ObjectNode)body.get("items").get(0).get("months").get(0);
        cell.set(field, json.readTree(value));
        save("EST", body.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); untouched();
    }
    @Test void duplicateMonthsRejected() throws Exception {
        save("EST", """
            {"items":[{"line_ty_cd":"ITEM","item_nm":"경비","months":[{"aloc_ym":"2026-10","aloc_amt":1},{"aloc_ym":"2026-10","mm_qty":1}]}]}
            """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aloc_ym")); untouched();
    }
}
