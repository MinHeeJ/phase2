package com.sicost.dispatchattendance;

import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.billing.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = DispatchAttendanceController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, DispatchAttendanceService.class,
    CurrentUser.class, PermissionService.class})
class DispatchAttendanceApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean DispatchAttendanceMapper mapper;
    @MockBean BillingMutationService mutations;
    String mode;
    final Map<Long, Input> saved = new LinkedHashMap<>();
    final List<BillingSupportModels.Audit> history = new ArrayList<>();
    static final String URL = "/api/dispatch-attendance?month=2026-10&projectId=1";
    @BeforeEach void setup() {
        mode = "DAY";
        saved.clear(); history.clear();
        when(mapper.project(1)).thenAnswer(i -> new Project("DP", mode));
        when(mapper.list(any())).thenAnswer(i -> {
            var input = saved.get(2L);
            return List.of(new Source(3, 2, input == null ? null : input.work_day_qty(),
                input == null ? null : input.rglr_tm_qty(), input == null ? null : input.ovtm_tm_qty(),
                input == null ? null : input.mth_work_day_qty(), new BigDecimal("0.80"), false));
        });
        when(mapper.existing(any())).thenAnswer(i -> saved.containsKey(2L) ? List.of(saved.get(2L)) : List.of());
        when(mapper.upsert(any())).thenAnswer(i -> {
            var write = i.getArgument(0, Write.class); saved.put(write.row().dev_id(), write.row()); return 9L;
        });
        doAnswer(i -> { history.add(i.getArgument(0)); return null; }).when(mutations).audit(any());
    }
    Map<String,Object> input() {
        var row = new LinkedHashMap<String,Object>();
        row.put("asgn_id",3); row.put("dev_id",2); row.put("work_day_qty",1);
        row.put("rglr_tm_qty",8); row.put("mth_work_day_qty",6);
        return row;
    }
    ResultActions save(Map<String,Object> row) throws Exception {
        return mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items", List.of(row)))));
    }
    void unchanged() { verify(mapper,never()).upsert(any()); assertThat(history).isEmpty(); }
    @Test void anonymousGetIncludesMissingAttendanceAndPriceWarningsWithoutWriting() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.project_id").value(1))
            .andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty())
            .andExpect(jsonPath("$.data.items[0].price_available").value(false))
            .andExpect(jsonPath("$.data.warnings").isNotEmpty());
        unchanged(); verifyNoInteractions(mutations);
    }
    @Test void anonymousDaySaveRoundsHalfUpAndAuditsWithoutPrice() throws Exception {
        save(input()).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0.17))
            .andExpect(jsonPath("$.data.items[0].assignment_mm").value(0.8))
            .andExpect(jsonPath("$.data.items[0].ovtm_tm_qty").value(0));
        assertThat(history).hasSize(1); assertThat(history.get(0).before()).isNull();
        assertThat(history.get(0).after()).contains("work_day_qty", "mth_work_day_qty");
        verify(mapper).upsert(argThat(w -> w.actor() == null));
        var order = inOrder(mutations, mapper);
        order.verify(mutations).lockProject(1); order.verify(mapper).project(1);
        order.verify(mutations).requireOpen(any(),eq("month"));
        mvc.perform(get(URL)).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0.17));
    }
    @Test void anonymousHourSaveRequiresNoInventedDays() throws Exception {
        mode="HOUR"; var row=input(); row.remove("work_day_qty"); row.put("rglr_tm_qty",0);
        save(row).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty())
            .andExpect(jsonPath("$.data.items[0].rglr_tm_qty").value(0))
            .andExpect(jsonPath("$.data.items[0].warnings").isNotEmpty());
    }
    @Test void zeroDayIsValidAndCalculatedZero() throws Exception {
        var row=input(); row.put("work_day_qty",0);
        save(row).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0));
    }
    @Test void updateHistoryContainsOldAndNewQuantities() throws Exception {
        save(input()); var row=input(); row.put("work_day_qty",2); save(row).andExpect(status().isOk());
        assertThat(saved).hasSize(1); assertThat(history).hasSize(2);
        assertThat(history.get(1).type()).isEqualTo("UPDATE");
        assertThat(history.get(1).before()).contains("1.0"); assertThat(history.get(1).after()).contains("2.0");
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","32","0.01","99999"})
    void denominatorInvalid(String value) throws Exception {
        var row=input(); row.put("mth_work_day_qty",new BigDecimal(value));
        save(row).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0].mth_work_day_qty")); unchanged();
    }
    @Test void denominatorRequired() throws Exception {
        var row=input(); row.remove("mth_work_day_qty"); save(row).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0].mth_work_day_qty")); unchanged();
    }
    @Test void dayRequiresWorkDays() throws Exception {
        var row=input(); row.remove("work_day_qty"); save(row).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0].work_day_qty")); unchanged();
    }
    @Test void hourRequiresRegularHours() throws Exception {
        mode="HOUR"; var row=input(); row.remove("rglr_tm_qty"); save(row).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0].rglr_tm_qty")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"work_day_qty","rglr_tm_qty","ovtm_tm_qty"})
    void quantitiesCannotBeNegative(String field) throws Exception {
        var row=input(); row.put(field,-1); save(row).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0]."+field)); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"work_day_qty","rglr_tm_qty","ovtm_tm_qty"})
    void quantitiesCannotSilentlyRound(String field) throws Exception {
        var row=input(); row.put(field,new BigDecimal("1.01")); save(row).andExpect(status().isBadRequest()); unchanged();
    }
    @Test void closedMonthRefusesWithoutMutation() throws Exception {
        doThrow(ApiException.conflict("CLOSED_MONTH","마감","month")).when(mutations).requireOpen(any(),any());
        save(input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); unchanged();
    }
    @Test void confirmedBillingRefusesWithoutMutation() throws Exception {
        when(mapper.billed(any())).thenReturn(true);
        save(input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED")); unchanged();
    }
    @Test void unrelatedAssignmentIsFieldError() throws Exception {
        var row=input(); row.put("asgn_id",99); save(row).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0].asgn_id")); unchanged();
    }
    @Test void wrongDeveloperIsFieldError() throws Exception {
        var row=input(); row.put("dev_id",99); save(row).andExpect(status().isBadRequest()); unchanged();
    }
    @Test void duplicateNaturalKeyRejectedBeforeWrites() throws Exception {
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items",List.of(input(),input())))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[1].dev_id")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"atnd_id","calculated_mm","created_by","assignment_mm"})
    void immutableAndUnknownFieldsRejected(String field) throws Exception {
        var row=input(); row.put(field,1); save(row).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0]."+field)); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"TK","MO","IN"})
    void onlyDispatchProjectAllowed(String type) throws Exception {
        when(mapper.project(1)).thenReturn(new Project(type,"DAY"));
        mvc.perform(get(URL)).andExpect(status().isBadRequest()); save(input()).andExpect(status().isBadRequest()); unchanged();
    }
    @Test void monthlyProjectCannotUseAttendance() throws Exception {
        mode="MONTH"; save(input()).andExpect(status().isBadRequest()); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"/api/dispatch-attendance?projectId=1", "/api/dispatch-attendance?month=2026-10",
        "/api/dispatch-attendance?month=2026-13&projectId=1", "/api/dispatch-attendance?month=2026-10&projectId=0"})
    void requiredQueryValidationForBothOperations(String url) throws Exception {
        mvc.perform(get(url)).andExpect(status().isBadRequest());
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
            .andExpect(status().isBadRequest()); unchanged();
    }
    @Test void februaryCalendarLimitNotWeekdayGuess() throws Exception {
        var row=input(); row.put("mth_work_day_qty",29);
        mvc.perform(put("/api/dispatch-attendance?month=2026-02&projectId=1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items",List.of(row)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[0].mth_work_day_qty")); unchanged();
    }
}
