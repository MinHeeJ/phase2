package com.sicost.staffplans;

import static com.sicost.staffplans.ProjectStaffPlanModels.*;
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

@WebMvcTest(controllers = ProjectStaffPlanController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectStaffPlanService.class,
    CurrentUser.class, PermissionService.class, ProjectStaffPlanApiTest.Time.class})
class ProjectStaffPlanApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectStaffPlanMapper mapper;
    final List<StaffPlan> plans = new ArrayList<>();
    final List<StaffHistory> history = new ArrayList<>();
    StaffProject project;
    @BeforeEach void setup() {
        plans.clear(); history.clear();
        project = new StaffProject("Y", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30));
        when(mapper.project(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? project : null);
        when(mapper.plans(anyLong())).thenAnswer(i -> List.copyOf(plans));
        when(mapper.actual(1)).thenReturn(List.of(new ActualMM("BE", new BigDecimal("3.50"))));
        when(mapper.activeCode(any())).thenAnswer(i -> {
            var code = i.getArgument(0, StaffCode.class);
            return Set.of("BE", "FE", "SENIOR", "JUNIOR").contains(code.code());
        });
        doAnswer(i -> { plans.removeIf(row -> row.src_need_id() == null); return null; }).when(mapper).deleteManual(anyLong());
        doAnswer(i -> {
            var write = i.getArgument(0, StaffWrite.class); var row = write.input();
            plans.add(new StaffPlan(row.plan_ym(), row.job_cd(), row.grd_cd(), row.need_mm_qty(), row.expc_mth_cst_amt(), write.count(), null));
            return null;
        }).when(mapper).insert(any());
        doAnswer(i -> { history.add(i.getArgument(0)); return null; }).when(mapper).history(any());
    }
    StaffPlan plan(String month, String job, String grade, String mm, Long source) {
        return new StaffPlan(month, job, grade, new BigDecimal(mm), null, new BigDecimal(mm).setScale(0, java.math.RoundingMode.CEILING), source);
    }
    String item(String month, String job, String grade, String mm) {
        return "{\"plan_ym\":\"" + month + "\",\"job_cd\":\"" + job + "\",\"grd_cd\":\"" + grade + "\",\"need_mm_qty\":" + mm + "}";
    }
    ResultActions save(String items) throws Exception {
        return mvc.perform(put("/api/projects/1/staff-plans").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"));
    }
    void untouched() { verify(mapper, never()).deleteManual(anyLong()); verify(mapper, never()).insert(any()); verify(mapper, never()).history(any()); }
    void error(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.requestId").isNotEmpty()); untouched();
    }
    @Test void anonymousReadReturnsMonthlyRowsAndJobActualWithoutWrites() throws Exception {
        plans.add(plan("2026-10", "BE", "", "2.00", null));
        mvc.perform(get("/api/projects/1/staff-plans")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items[0].grd_cd").value(""))
            .andExpect(jsonPath("$.data.items[0].need_psn_cnt").value(2)).andExpect(jsonPath("$.data.actual[0].mm_qty").value(3.5));
        untouched();
    }
    @Test void anonymousSaveTwoMonthsAndReadbackFourPlannedVersusThreePointFiveActual() throws Exception {
        save(item("2026-10", "BE", "", "2.00") + "," + item("2026-11", "BE", "", "2.00"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        var body = mvc.perform(get("/api/projects/1/staff-plans")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).get("data");
        BigDecimal total = BigDecimal.ZERO;
        for (var row : data.get("items")) total = total.add(row.get("need_mm_qty").decimalValue());
        assertThat(total).isEqualByComparingTo("4.00");
        assertThat(data.get("actual").get(0).get("mm_qty").decimalValue().subtract(total)).isEqualByComparingTo("-0.50");
        assertThat(history).hasSize(1); assertThat(history.get(0).actor()).isNull();
    }
    @Test void anonymousEmptyReplacementDeletesManualAndAllowsEmptyPlanWithActualSummary() throws Exception {
        plans.add(plan("2026-10", "BE", "", "2", null));
        mvc.perform(put("/api/projects/1/staff-plans").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[]}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.actual[0].mm_qty").value(3.5));
        assertThat(plans).isEmpty(); assertThat(history).hasSize(1);
        verify(mapper).deleteManual(1);
        verify(mapper, never()).insert(any());
    }
    @ParameterizedTest @ValueSource(strings = {"100", "2.001", "-0.01", "1.00000000000000001", "\"2\"", "null"})
    void invalidMmNeverDeletesOrAudits(String mm) throws Exception {
        plans.add(plan("2026-10", "BE", "", "2", null));
        error(save(item("2026-10", "BE", "", mm)), "items[0].need_mm_qty"); assertThat(plans).hasSize(1);
    }
    @Test void inactiveJobIsFieldErrorAndUnchanged() throws Exception { error(save(item("2026-10", "OLD", "", "2")), "items[0].job_cd"); }
    @Test void inactiveGradeIsFieldErrorAndUnchanged() throws Exception { error(save(item("2026-10", "BE", "OLD", "2")), "items[0].grd_cd"); }
    @Test void zeroCellsOmittedAndCeilingMaterializesThree() throws Exception {
        save(item("2026-10", "BE", "", "2.01") + "," + item("2026-11", "BE", "", "0"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].need_psn_cnt").value(3));
    }
    @Test void sourcedRowsPreservedWhileOnlyManualRowsAreReplaced() throws Exception {
        var sourced = plan("2026-10", "FE", "SENIOR", "1", 7L); plans.add(sourced);
        plans.add(plan("2026-10", "BE", "", "8", null));
        mvc.perform(put("/api/projects/1/staff-plans").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[" + item("2026-11", "BE", "", "2.01") + "]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        assertThat(plans).hasSize(2).contains(sourced); assertThat(history).hasSize(1);
        verify(mapper).insert(argThat(w -> w.count().compareTo(new BigDecimal("3")) == 0));
    }
    @Test void sourcedKeyCollisionIs400BeforeDelete() throws Exception {
        plans.add(plan("2026-10", "BE", "", "1", 7L));
        error(save(item("2026-10", "BE", "", "2")), "items"); assertThat(plans.get(0).src_need_id()).isEqualTo(7L);
    }
    @Test void duplicateManualKeyIs400BeforeDelete() throws Exception {
        error(save(item("2026-10", "BE", "", "2") + "," + item("2026-10", "BE", "", "3")), "items");
    }
    @Test void outsidePeriodNewCellRejected() throws Exception { error(save(item("2026-12", "BE", "", "2")), "items[0].plan_ym"); }
    @Test void unchangedOutsidePeriodIsPreservedAndCanBeExplicitlyRemoved() throws Exception {
        plans.add(plan("2026-12", "BE", "", "2", null));
        save(item("2026-12", "BE", "", "2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].plan_ym").value("2026-12"));
        save("").andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }
    @Test void changedOutsidePeriodMmRejected() throws Exception {
        plans.add(plan("2026-12", "BE", "", "2", null));
        error(save(item("2026-12", "BE", "", "3")), "items[0].plan_ym");
    }
    @Test void disabledProjectReadAllowedButSaveDoesNotMutateOrAudit() throws Exception {
        project = new StaffProject("N", project.start(), project.end());
        mvc.perform(get("/api/projects/1/staff-plans")).andExpect(status().isOk()); error(save(""), "use_yn");
    }
    @ParameterizedTest @ValueSource(strings = {"get", "put"}) void absentProjectIs404(String method) throws Exception {
        var request = method.equals("get") ? get("/api/projects/99/staff-plans") : put("/api/projects/99/staff-plans").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}");
        mvc.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"get", "put"}) void nonPositiveIdIs400(String method) throws Exception {
        var request = method.equals("get") ? get("/api/projects/0/staff-plans") : put("/api/projects/0/staff-plans").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}");
        error(mvc.perform(request), "id");
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"items\":null}", "{\"items\":{}}", "{\"items\":[null]}"})
    void malformedList(String body) throws Exception { error(mvc.perform(put("/api/projects/1/staff-plans").contentType(MediaType.APPLICATION_JSON).content(body)), "items"); }
    @Test void missingJobRejected() throws Exception { error(save("{\"plan_ym\":\"2026-10\",\"need_mm_qty\":1}"), "items[0].job_cd"); }
    @Test void invalidMonthRejected() throws Exception { error(save(item("2026-13", "BE", "", "1")), "items[0].plan_ym"); }
    @Test void amountOverflowRejected() throws Exception { error(save(item("2026-10", "BE", "", "1").replace("}", ",\"expc_mth_cst_amt\":9223372036854775808}")), "items[0].expc_mth_cst_amt"); }
    @Test void missingGradeNormalizesToEmptyString() throws Exception {
        save("{\"plan_ym\":\"2026-10\",\"job_cd\":\"BE\",\"need_mm_qty\":1}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].grd_cd").value(""));
    }
    @Test void targetValuesCannotBeSentToStaffPlanPut() throws Exception {
        error(mvc.perform(put("/api/projects/1/staff-plans").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[],\"trgt_cst_amt\":100}")), "trgt_cst_amt");
    }
    @Test void historyOneSummaryWithBeforeAndAfterAndNullActor() throws Exception {
        plans.add(plan("2026-10", "BE", "", "9", null));
        save(item("2026-10", "BE", "", "2") + "," + item("2026-11", "BE", "", "2")).andExpect(status().isOk());
        assertThat(history).containsExactly(new StaffHistory("1", "인원 계획 1건 · 합계 9.00 MM", "인원 계획 2건 · 합계 4.00 MM", null));
        verify(mapper, times(1)).history(any());
    }
}
