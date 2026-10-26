package com.sicost.billstages;

import static com.sicost.billstages.ProjectBillStageModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.ProjectValues;
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

@WebMvcTest(controllers = ProjectBillStageController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectBillStageService.class,
    CurrentUser.class, PermissionService.class, ProjectBillStageApiTest.Time.class})
class ProjectBillStageApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectBillStageMapper mapper;
    final Map<Long, Stage> rows = new LinkedHashMap<>();
    final List<StageHistory> history = new ArrayList<>();
    long next;
    final LocalDate date = LocalDate.of(2026, 11, 1);

    @BeforeEach void setup() {
        rows.clear(); history.clear(); next = 10;
        when(mapper.project(anyLong())).thenAnswer(i -> project(i.getArgument(0)));
        when(mapper.lockProject(anyLong())).thenAnswer(i -> project(i.getArgument(0)));
        when(mapper.stages(anyLong())).thenAnswer(i -> rows.values().stream()
            .sorted(Comparator.comparingInt(Stage::stg_odr_no)).toList());
        when(mapper.insert(any())).thenAnswer(i -> {
            var write = i.getArgument(0, StageWrite.class);
            long id = next++;
            rows.put(id, stage(id, write.input(), write.ordinal(), false));
            return id;
        });
        doAnswer(i -> {
            var w = i.getArgument(0, StageWrite.class);
            rows.put(w.input().bill_stg_id(), stage(w.input().bill_stg_id(), w.input(), w.ordinal(), false));
            return null;
        }).when(mapper).update(any());
        doAnswer(i -> {
            var p = i.getArgument(0, StagePosition.class); var old = rows.get(p.stageId());
            rows.put(p.stageId(), new Stage(old.bill_stg_id(), old.bill_stg_nm(), old.sply_amt(),
                old.bill_plan_dt(), p.ordinal(), old.vat_amt(), old.billed()));
            return null;
        }).when(mapper).position(any());
        doAnswer(i -> { rows.remove(i.getArgument(0, StageKey.class).stageId()); return null; }).when(mapper).delete(any());
        doAnswer(i -> { history.add(i.getArgument(0)); return null; }).when(mapper).history(any());
    }
    ProjectState project(long id) {
        return switch ((int) id) {
            case 1 -> new ProjectState(id, "TK", "Y", 500000000L);
            case 2 -> new ProjectState(id, "MO", "Y", 500000000L);
            case 3 -> new ProjectState(id, "TK", "N", 500000000L);
            default -> null;
        };
    }
    Stage stage(long id, StageInput input, int order, boolean billed) {
        return new Stage(id, input.bill_stg_nm(), input.sply_amt(), input.bill_plan_dt(), order,
            ProjectValues.vat(input.sply_amt()), billed);
    }
    Map<String, Object> input(String name, long amount) {
        return new LinkedHashMap<>(Map.of("bill_stg_nm", name, "sply_amt", amount, "bill_plan_dt", date.toString()));
    }
    ResultActions save(long id, List<?> inputs) throws Exception {
        return mvc.perform(put("/api/projects/{id}/bill-stages", id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items", inputs))));
    }
    void untouched() {
        verify(mapper, never()).insert(any()); verify(mapper, never()).delete(any());
        verify(mapper, never()).position(any()); verify(mapper, never()).update(any());
        verify(mapper, never()).history(any());
    }
    void invalid(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.requestId").isNotEmpty())
            .andExpect(jsonPath("$.success").value(false)); untouched();
    }
    @Test void anonymousListReadsAllColumnsAndHasNoWrites() throws Exception {
        rows.put(7L, stage(7, new StageInput(7L, "착수금", 101, date), 1, true));
        mvc.perform(get("/api/projects/1/bill-stages")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items[0].bill_stg_id").value(7))
            .andExpect(jsonPath("$.data.items[0].bill_stg_nm").value("착수금"))
            .andExpect(jsonPath("$.data.items[0].bill_plan_dt").value("2026-11-01"))
            .andExpect(jsonPath("$.data.items[0].sply_amt").value(101))
            .andExpect(jsonPath("$.data.items[0].vat_amt").value(10))
            .andExpect(jsonPath("$.data.items[0].billed").value(true)); untouched();
    }
    @Test void anonymousThreeStagesReadBackContiguousAndOneSummary() throws Exception {
        mvc.perform(put("/api/projects/1/bill-stages").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items", List.of(input("착수금", 100000000),
                input("중도금", 200000000), input("잔금", 200000000))))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalSupplyAmount").value(500000000));
        mvc.perform(get("/api/projects/1/bill-stages")).andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].stg_odr_no").value(1))
            .andExpect(jsonPath("$.data.items[1].stg_odr_no").value(2))
            .andExpect(jsonPath("$.data.items[2].stg_odr_no").value(3));
        assertThat(history).containsExactly(new StageHistory("1", "청구 단계 0건 · 합계 0원", "청구 단계 3건 · 합계 500,000,000원", null));
    }
    @Test void oneStageReplacesWholeListAnonymously() throws Exception {
        rows.put(7L, stage(7, new StageInput(7L, "기존", 20, date), 1, false));
        save(1, List.of(input("한 단계", 500000000))).andExpect(status().isOk());
        mvc.perform(get("/api/projects/1/bill-stages")).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].bill_stg_nm").value("한 단계"))
            .andExpect(jsonPath("$.data.items[0].sply_amt").value(500000000));
        assertThat(rows).doesNotContainKey(7L);
    }
    @Test void mismatchIsSavedWithBothTotals() throws Exception {
        save(1, List.of(input("합계 차이", 490000000))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cur_cntrct_amt").value(500000000))
            .andExpect(jsonPath("$.data.totalSupplyAmount").value(490000000));
    }
    @Test void emptyReplacementAllowed() throws Exception {
        save(1, List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        assertThat(history).hasSize(1);
    }
    @Test void exactlyTwentyAllowed() throws Exception { save(1, Collections.nCopies(20, input("단계", 1))).andExpect(status().isOk()); }
    @Test void twentyOneRejected() throws Exception { invalid(save(1, Collections.nCopies(21, input("단계", 1))), "items"); }
    @Test void zeroRejected() throws Exception { invalid(save(1, List.of(input("단계", 0))), "items[0].sply_amt"); }
    @Test void missingDateRejected() throws Exception {
        var row = input("단계", 1); row.remove("bill_plan_dt");
        invalid(save(1, List.of(row)), "items[0].bill_plan_dt");
    }
    @Test void missingNameRejected() throws Exception { invalid(save(1, List.of(input(" ", 1))), "items[0].bill_stg_nm"); }
    @Test void longNameRejected() throws Exception { invalid(save(1, List.of(input("가".repeat(101), 1))), "items[0].bill_stg_nm"); }
    @Test void hundredCharacterNameAllowed() throws Exception { save(1, List.of(input("가".repeat(100), 1))).andExpect(status().isOk()); }
    @ParameterizedTest @ValueSource(strings = {"0", "1.5", "\"1\"", "9223372036854775808", "null"})
    void invalidMoneyIsFlatFieldError(String amount) throws Exception {
        invalid(mvc.perform(put("/api/projects/1/bill-stages").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[{\"bill_stg_nm\":\"단계\",\"sply_amt\":" + amount + ",\"bill_plan_dt\":\"2026-11-01\"}]}")), "items[0].sply_amt");
    }
    @Test void sumOverflowRejectedBeforeWrites() throws Exception {
        invalid(save(1, List.of(input("첫 단계", Long.MAX_VALUE), input("다음", 1))), "items");
    }
    @Test void moReadIs400() throws Exception { invalid(mvc.perform(get("/api/projects/2/bill-stages")), "cntrct_ty_cd"); }
    @Test void moWriteIs400() throws Exception { invalid(save(2, List.of(input("단계", 1))), "cntrct_ty_cd"); }
    @Test void disabledWriteIs400() throws Exception { invalid(save(3, List.of(input("단계", 1))), "use_yn"); }
    @Test void disabledReadStillAllowed() throws Exception { mvc.perform(get("/api/projects/3/bill-stages")).andExpect(status().isOk()); untouched(); }
    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"}) void missingProject(String method) throws Exception {
        if (method.equals("GET")) mvc.perform(get("/api/projects/99/bill-stages")).andExpect(status().isNotFound());
        else save(99, List.of(input("단계", 1))).andExpect(status().isNotFound());
        untouched();
    }
    @Test void foreignStageIs404() throws Exception {
        var row = input("외부", 1); row.put("bill_stg_id", 99);
        save(1, List.of(row)).andExpect(status().isNotFound()); untouched();
    }
    @Test void duplicateRetainedIdentityIs400() throws Exception {
        rows.put(7L, stage(7, new StageInput(7L, "단계", 1, date), 1, false));
        var row = input("단계", 1); row.put("bill_stg_id", 7);
        invalid(save(1, List.of(row, row)), "items[1].bill_stg_id");
    }
    @ParameterizedTest @ValueSource(strings = {"name", "amount", "date", "delete", "renumber", "omit-id"})
    void billedChangesAre409AndUntouched(String kind) throws Exception {
        var original = stage(7, new StageInput(7L, "청구 단계", 100, date), 1, true); rows.put(7L, original);
        var row = input("청구 단계", 100); row.put("bill_stg_id", 7);
        switch (kind) {
            case "name" -> row.put("bill_stg_nm", "수정");
            case "amount" -> row.put("sply_amt", 200);
            case "date" -> row.put("bill_plan_dt", "2026-12-01");
            case "omit-id" -> row.remove("bill_stg_id");
        }
        List<?> request = kind.equals("delete") ? List.of() : kind.equals("renumber")
            ? List.of(input("앞단계", 1), row) : List.of(row);
        mvc.perform(put("/api/projects/1/bill-stages").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items", request))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAGE_BILLED"));
        untouched(); assertThat(rows.values()).containsExactly(original);
    }
    @Test void retainedBilledStageIsNeverUpdated() throws Exception {
        var original = stage(7, new StageInput(7L, "청구 단계", 100, date), 1, true); rows.put(7L, original);
        var row = input("청구 단계", 100); row.put("bill_stg_id", 7);
        save(1, List.of(row, input("다음", 200))).andExpect(status().isOk());
        verify(mapper, never()).position(any()); verify(mapper, never()).update(any());
        assertThat(rows.get(7L)).isSameAs(original);
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "[]", "{\"items\":null}", "{\"items\":{}}", "{\"items\":[]}{}"})
    void malformedBody(String body) throws Exception {
        invalid(mvc.perform(put("/api/projects/1/bill-stages").contentType(MediaType.APPLICATION_JSON).content(body)), "items");
    }
    @Test void badDateRejected() throws Exception {
        var row = input("단계", 1); row.put("bill_plan_dt", "2026-02-30");
        invalid(save(1, List.of(row)), "items[0].bill_plan_dt");
    }
    @Test void invalidProjectId() throws Exception { invalid(mvc.perform(get("/api/projects/0/bill-stages")), "id"); }
}
