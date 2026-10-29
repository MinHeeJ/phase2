package com.sicost.dispatchattendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.*;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DispatchAttendanceController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, DispatchAttendanceService.class, CurrentUser.class, PermissionService.class})
class DispatchAttendanceApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean DispatchAttendanceMapper mapper;
    final Map<Long, Stored> rows = new LinkedHashMap<>();
    String method;
    boolean billed;
    long assignmentCount;
    @BeforeEach void setup() {
        rows.clear(); method = "DAY"; billed = false; assignmentCount = 1;
        when(mapper.project(1)).thenAnswer(i -> new Project("DP", method));
        when(mapper.lockProject(1)).thenAnswer(i -> new Project("DP", method));
        when(mapper.lockAttendance(any())).thenAnswer(i -> List.copyOf(rows.values()));
        when(mapper.sources(any())).thenAnswer(i -> {
            var row = rows.get(11L);
            return List.of(new Source(21, 11, new BigDecimal("0.75"), false, assignmentCount, billed,
                row == null ? null : row.work_day_qty(), row == null ? null : row.rglr_tm_qty(),
                row == null ? null : row.ovtm_tm_qty(), row == null ? null : row.mth_work_day_qty()));
        });
        when(mapper.upsert(any())).thenAnswer(i -> {
            var input = i.getArgument(0, AttendanceWrite.class).input();
            rows.put(input.dev_id(), new Stored(31, input.asgn_id(), input.dev_id(), input.work_day_qty(),
                input.rglr_tm_qty(), input.ovtm_tm_qty(), input.mth_work_day_qty(), null));
            return 31L;
        });
    }
    Map<String, Object> row() {
        return new LinkedHashMap<>(Map.of("asgn_id", 21, "dev_id", 11, "work_day_qty", 1,
            "rglr_tm_qty", 8, "mth_work_day_qty", 8));
    }
    ResultActions save(Map<String, Object> row) throws Exception {
        return mvc.perform(put("/api/dispatch-attendance").param("month", "2026-10").param("projectId", "1")
            .contentType("application/json").content(json.writeValueAsString(Map.of("items", List.of(row)))));
    }
    void untouched() {
        verify(mapper, never()).upsert(any());
        verify(mapper, never()).audit(any());
    }
    @Test void anonymousReadListsCandidatesWarningsAndNullsWithoutWrites() throws Exception {
        mvc.perform(get("/api/dispatch-attendance").param("month", "2026-10").param("projectId", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.project_id").value(1))
            .andExpect(jsonPath("$.data.items[0].asgn_id").value(21))
            .andExpect(jsonPath("$.data.items[0].price_available").value(false))
            .andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty());
        untouched(); verify(mapper, never()).lockProject(anyLong());
    }
    @ParameterizedTest @ValueSource(strings = {"DAY", "HOUR"})
    void anonymousHappySaveAllowsMissingPriceAndZeroQuantities(String basis) throws Exception {
        method = basis;
        var input = row(); input.put("work_day_qty", 0); input.put("rglr_tm_qty", 0);
        save(input).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0))
            .andExpect(jsonPath("$.data.items[0].ovtm_tm_qty").value(0))
            .andExpect(jsonPath("$.data.items[0].price_available").value(false));
        var order = inOrder(mapper);
        order.verify(mapper).lockProject(1);
        order.verify(mapper).monthStatus("2026-10");
        order.verify(mapper).lockAttendance(any());
        order.verify(mapper).sources(any());
        order.verify(mapper).upsert(argThat(w -> w.actor() == null && w.scope().projectId() == 1));
        order.verify(mapper).audit(argThat(a -> a.actor() == null && a.type().equals("INSERT") && a.id().equals("31")));
    }
    @Test void halfUpMmAndFullBeforeAfterAuditAreReturnedWithoutSourceMutation() throws Exception {
        rows.put(11L, new Stored(31, 21L, 11, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.TEN, 100L));
        save(row()).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0.13))
            .andExpect(jsonPath("$.data.items[0].assignment_mm").value(0.75));
        var captor = org.mockito.ArgumentCaptor.forClass(AttendanceAudit.class);
        verify(mapper).audit(captor.capture());
        var audit = captor.getValue();
        assertThat(audit.type()).isEqualTo("UPDATE");
        assertThat(json.readTree(audit.before()).get("work_day_qty").decimalValue()).isEqualByComparingTo("0");
        assertThat(json.readTree(audit.after()).get("work_day_qty").decimalValue()).isEqualByComparingTo("1");
        assertThat(json.readTree(audit.after()).get("upld_id").isNull()).isTrue();
    }
    @Test void hourWithoutOptionalDaysHasNullMmAndWarning() throws Exception {
        method = "HOUR"; var input = row(); input.remove("work_day_qty");
        save(input).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty())
            .andExpect(jsonPath("$.data.items[0].warnings").isNotEmpty());
    }
    @ParameterizedTest @CsvSource({
        "mth_work_day_qty,0", "mth_work_day_qty,-1", "mth_work_day_qty,32", "mth_work_day_qty,1.01",
        "work_day_qty,-1", "work_day_qty,1.11", "rglr_tm_qty,-1", "rglr_tm_qty,100000",
        "ovtm_tm_qty,-1", "ovtm_tm_qty,0.01", "asgn_id,0", "dev_id,0"
    })
    void invalidNumbersAreFieldValidationErrors(String field, String value) throws Exception {
        var input = row(); input.put(field, new BigDecimal(value));
        save(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("items[0]." + field)).andExpect(jsonPath("$.requestId").isNotEmpty());
        untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"mth_work_day_qty", "work_day_qty", "asgn_id", "dev_id"})
    void missingDayRequiredFieldIs400(String field) throws Exception {
        var input = row(); input.remove(field);
        save(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("items[0]." + field)); untouched();
    }
    @Test void missingHourRequiredTimeIs400() throws Exception {
        method = "HOUR"; var input = row(); input.remove("rglr_tm_qty");
        save(input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[0].rglr_tm_qty")); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void missingAndMalformedQueryValuesAreExplicitValidation(String verb) throws Exception {
        for (String query : List.of("", "?month=2026-13&projectId=1", "?month=2026-10", "?month=2026-10&projectId=bad")) {
            mvc.perform((verb.equals("GET") ? get("/api/dispatch-attendance" + query) : put("/api/dispatch-attendance" + query))
                .contentType("application/json").content("{\"items\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        untouched();
    }
    @Test void invalidFebruaryDenominatorUsesCalendarDaysNotWeekdays() throws Exception {
        mvc.perform(put("/api/dispatch-attendance?month=2026-02&projectId=1").contentType("application/json")
            .content(json.writeValueAsString(Map.of("items", List.of(rowWithDays(29))))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[0].mth_work_day_qty")); untouched();
    }
    Map<String, Object> rowWithDays(int days) { var input = row(); input.put("mth_work_day_qty", days); return input; }
    @Test void closedMonthConflictsAndPreservesOriginal() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        save(row()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); untouched();
    }
    @Test void confirmedUseConflictsAndPreservesOriginal() throws Exception {
        billed = true;
        save(row()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED")); untouched();
    }
    @Test void ambiguityRequiresExplicitResolution() throws Exception {
        assignmentCount = 2;
        save(row()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[0].asgn_id")); untouched();
    }
    @Test void foreignOrInactiveOrNonoverlappingAssignmentCannotBeSelected() throws Exception {
        var input = row(); input.put("asgn_id", 99);
        save(input).andExpect(status().isBadRequest()); untouched();
    }
    @Test void personMustMatchAssignment() throws Exception {
        var input = row(); input.put("dev_id", 99);
        save(input).andExpect(status().isBadRequest()); untouched();
    }
    @Test void duplicatePersonBatchRejectsWithoutWrites() throws Exception {
        mvc.perform(put("/api/dispatch-attendance?month=2026-10&projectId=1").contentType("application/json")
            .content(json.writeValueAsString(Map.of("items", List.of(row(), row())))))
            .andExpect(status().isBadRequest()); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"MONTH", "STAGE", ""})
    void invalidProjectMethodCannotReadOrWrite(String basis) throws Exception {
        method = basis;
        save(row()).andExpect(status().isBadRequest()); untouched();
        mvc.perform(get("/api/dispatch-attendance?month=2026-10&projectId=1")).andExpect(status().isBadRequest());
    }
    @Test void unknownProjectIsField400() throws Exception {
        mvc.perform(get("/api/dispatch-attendance?month=2026-10&projectId=99"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("projectId")); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "{", "{\"items\":[]}{\"items\":[]}", "{\"items\":null}"})
    void malformedBodiesUseBillingError(String body) throws Exception {
        mvc.perform(put("/api/dispatch-attendance?month=2026-10&projectId=1").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); untouched();
    }
}
