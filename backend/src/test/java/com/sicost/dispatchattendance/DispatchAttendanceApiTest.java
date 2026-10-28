package com.sicost.dispatchattendance;

import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = DispatchAttendanceController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, DispatchAttendanceService.class,
    CurrentUser.class, PermissionService.class, DispatchAttendanceApiTest.Time.class})
class DispatchAttendanceApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean DispatchAttendanceMapper mapper;
    Project project;
    Map<Long, Input> saved;
    List<Audit> audits;
    boolean price;
    static final String BASE = "/api/dispatch-attendance";
    @BeforeEach void setup() {
        project = new Project("DP", "DAY", "Y");
        saved = new LinkedHashMap<>(); audits = new ArrayList<>(); price = true;
        when(mapper.project(1)).thenAnswer(i -> project);
        when(mapper.lockProject(1)).thenAnswer(i -> project);
        when(mapper.sources(any())).thenAnswer(i -> {
            var input = saved.get(10L);
            return List.of(new Source(20L, 10L, new BigDecimal("1.00"), price,
                input == null ? null : input.work_day_qty(), input == null ? null : input.rglr_tm_qty(),
                input == null ? null : input.ovtm_tm_qty(), input == null ? null : input.mth_work_day_qty()));
        });
        when(mapper.existing(any())).thenAnswer(i -> saved.get(i.getArgument(0, Write.class).item().dev_id()));
        when(mapper.upsert(any())).thenAnswer(i -> {
            var w = i.getArgument(0, Write.class); saved.put(w.item().dev_id(), w.item()); return 30L;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    Map<String,Object> item() {
        var item = new LinkedHashMap<String,Object>();
        item.put("asgn_id", 20L); item.put("dev_id", 10L);
        item.put("work_day_qty", 1); item.put("mth_work_day_qty", 8);
        return item;
    }
    ResultActions save(Map<String,Object> item) throws Exception {
        return putBody("2026-10", Map.of("items", List.of(item)));
    }
    ResultActions putBody(String month, Object body) throws Exception {
        return mvc.perform(put(BASE).param("month", month).param("projectId", "1")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    void unchanged() {
        verify(mapper, never()).upsert(any());
        verify(mapper, never()).audit(any());
    }
    @Test void anonymousGetListsEligibleAssignmentsWithMissingAttendanceWarning() throws Exception {
        mvc.perform(get(BASE).param("month", "2026-10").param("projectId", "1"))
            .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.project_id").value(1))
            .andExpect(jsonPath("$.data.items[0].asgn_id").value(20))
            .andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty())
            .andExpect(jsonPath("$.data.items[0].warnings").isNotEmpty());
        unchanged();
    }
    @Test void anonymousDaySaveRoundsHalfUpAndAuditsWithoutSourceMutation() throws Exception {
        save(item()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].calculated_mm").value(0.13))
            .andExpect(jsonPath("$.data.items[0].assignment_mm").value(1.00));
        assertThat(saved.get(10L).ovtm_tm_qty()).isEqualByComparingTo("0");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).actor()).isNull();
        assertThat(audits.get(0).before()).isNull();
        assertThat(json.readTree(audits.get(0).after()).path("work_day_qty").intValue()).isEqualTo(1);
    }
    @Test void hourSaveAllowsAbsentDaysAndZeroRegularHours() throws Exception {
        project = new Project("DP", "HOUR", "Y");
        var input = item(); input.remove("work_day_qty"); input.put("rglr_tm_qty", 0);
        save(input).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty())
            .andExpect(jsonPath("$.data.items[0].rglr_tm_qty").value(0));
    }
    @Test void zeroDaysAreValid() throws Exception {
        var input = item(); input.put("work_day_qty", 0);
        save(input).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0));
    }
    @Test void missingPriceDoesNotBlockManualSave() throws Exception {
        price = false;
        save(item()).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].price_available").value(false))
            .andExpect(jsonPath("$.data.warnings").isNotEmpty());
    }
    @Test void updateAuditContainsOldAndNewValues() throws Exception {
        save(item()).andExpect(status().isOk());
        var changed = item(); changed.put("work_day_qty", 2); changed.put("mth_work_day_qty", 10);
        save(changed).andExpect(status().isOk());
        assertThat(audits.get(1).type()).isEqualTo("UPDATE");
        assertThat(json.readTree(audits.get(1).before()).path("work_day_qty").asInt()).isEqualTo(1);
        assertThat(json.readTree(audits.get(1).after()).path("work_day_qty").asInt()).isEqualTo(2);
        assertThat(json.readTree(audits.get(1).after()).path("mth_work_day_qty").asInt()).isEqualTo(10);
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "32"})
    void invalidDenominatorIsField400(String value) throws Exception {
        var input = item(); input.put("mth_work_day_qty", new BigDecimal(value));
        save(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("items[0].mth_work_day_qty"));
        unchanged();
    }
    @Test void denominatorCannotExceedFebruaryCalendarDays() throws Exception {
        var input = item(); input.put("mth_work_day_qty", 29);
        putBody("2027-02", Map.of("items", List.of(input))).andExpect(status().isBadRequest());
        unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"mth_work_day_qty", "work_day_qty", "asgn_id", "dev_id"})
    void missingRequiredInputIsField400(String field) throws Exception {
        var input = item(); input.remove(field);
        save(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[0]." + field));
        unchanged();
    }
    @Test void hourRequiresRegularHours() throws Exception {
        project = new Project("DP", "HOUR", "Y");
        save(item()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[0].rglr_tm_qty"));
        unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"work_day_qty", "rglr_tm_qty", "ovtm_tm_qty"})
    void negativeQuantitiesAreRejected(String field) throws Exception {
        var input = item(); input.put(field, -1);
        save(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[0]." + field));
        unchanged();
    }
    @Test void numericStringsAreNotSilentlyCoerced() throws Exception {
        var input = item(); input.put("work_day_qty", "1");
        save(input).andExpect(status().isBadRequest()); unchanged();
    }
    @Test void mismatchedDeveloperAssignmentIsRejected() throws Exception {
        var input = item(); input.put("asgn_id", 21);
        save(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[0].asgn_id"));
        unchanged();
    }
    @Test void duplicateDeveloperRejectsWholeBatchBeforeAnyWrites() throws Exception {
        putBody("2026-10", Map.of("items", List.of(item(), item()))).andExpect(status().isBadRequest());
        unchanged();
    }
    @Test void laterInvalidItemPreventsEarlierValidWrite() throws Exception {
        var other = item(); other.put("dev_id", 11);
        putBody("2026-10", Map.of("items", List.of(item(), other))).andExpect(status().isBadRequest());
        unchanged();
    }
    @Test void closedMonthRejectsBeforeAnyMutation() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        save(item()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        unchanged();
    }
    @Test void confirmedUsageRejectsBeforeAnyMutation() throws Exception {
        when(mapper.billed(any())).thenReturn(true);
        save(item()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED"));
        unchanged();
    }
    @Test void pastYearWriteIsValidationError() throws Exception {
        putBody("2025-12", Map.of("items", List.of(item()))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("month")); unchanged();
    }
    @Test void pastYearReadRemainsAvailable() throws Exception {
        mvc.perform(get(BASE).param("month", "2025-12").param("projectId", "1")).andExpect(status().isOk());
        unchanged();
    }
    @ParameterizedTest @CsvSource({"TK,STAGE", "MO,MONTH", "DP,MONTH", "IN,MONTH"})
    void unsupportedProjectTypeOrSettlementIs400(String type, String method) throws Exception {
        project = new Project(type, method, "Y");
        save(item()).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("month", "2026-10").param("projectId", "1"))
            .andExpect(status().isBadRequest()); unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"month", "projectId"})
    void bothOperationsRequireScope(String missing) throws Exception {
        var read = get(BASE); var write = put(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}");
        if (!missing.equals("month")) { read.param("month", "2026-10"); write.param("month", "2026-10"); }
        if (!missing.equals("projectId")) { read.param("projectId", "1"); write.param("projectId", "1"); }
        mvc.perform(read).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(write).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        unchanged();
    }
    @Test void malformedBodyHasSafeErrorEnvelope() throws Exception {
        mvc.perform(put(BASE).param("month", "2026-10").param("projectId", "1")
            .contentType(MediaType.APPLICATION_JSON).content("{broken"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        unchanged();
    }
    @Test void auditErrorIsSanitizedAtHttpBoundary() throws Exception {
        doThrow(new IllegalStateException("private database detail")).when(mapper).audit(any());
        save(item()).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
}
