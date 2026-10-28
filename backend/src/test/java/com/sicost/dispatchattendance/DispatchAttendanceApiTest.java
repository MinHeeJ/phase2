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
@Import({RequestIdFilter.class, ApiExceptionHandler.class, DispatchAttendanceExceptionHandler.class,
    DispatchAttendanceService.class, CurrentUser.class, PermissionService.class})
class DispatchAttendanceApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean DispatchAttendanceMapper mapper;
    final Map<Long, Stored> rows = new LinkedHashMap<>();
    final List<Audit> audits = new ArrayList<>();
    Project project;
    List<Assignment> assignments;
    static final String BASE = "/api/dispatch-attendance?month=2026-10&projectId=1";
    @BeforeAll static void durableContractDeclaresBothOperations() throws Exception {
        com.sicost.support.OpenApiFixture.assertOperation("/api/dispatch-attendance", "get", "getDispatchAttendance");
        com.sicost.support.OpenApiFixture.assertOperation("/api/dispatch-attendance", "put", "saveDispatchAttendance");
    }
    @BeforeEach void setup() {
        rows.clear(); audits.clear();
        project = new Project("DP", "DAY", "Y");
        assignments = List.of(new Assignment(10, 20, new BigDecimal("0.50"), false),
            new Assignment(11, 21, BigDecimal.ONE, true));
        when(mapper.project(1)).thenAnswer(i -> project);
        when(mapper.lockProject(1)).thenAnswer(i -> project);
        when(mapper.assignments(any())).thenAnswer(i -> assignments);
        when(mapper.attendance(any())).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(mapper.lockAttendance(any())).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(mapper.confirmedAssignments(any())).thenReturn(List.of());
        when(mapper.upsert(any())).thenAnswer(i -> {
            var w = i.getArgument(0, Write.class); var r = w.item();
            long id = rows.containsKey(r.dev_id()) ? rows.get(r.dev_id()).atnd_id() : r.dev_id() + 100;
            rows.put(r.dev_id(), new Stored(id, r.asgn_id(), r.dev_id(), r.work_day_qty(),
                r.rglr_tm_qty(), r.ovtm_tm_qty(), r.mth_work_day_qty()));
            return id;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    Map<String,Object> item() {
        var input = new LinkedHashMap<String,Object>();
        input.put("asgn_id",10); input.put("dev_id",20);
        input.put("work_day_qty",10); input.put("mth_work_day_qty",21);
        return input;
    }
    ResultActions save(Map<String,Object> item) throws Exception {
        return mvc.perform(put("/api/dispatch-attendance").param("month", "2026-10").param("projectId", "1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items", List.of(item)))));
    }
    void unchanged() {
        verify(mapper, never()).upsert(any()); verify(mapper, never()).audit(any());
        assertThat(rows).isEmpty();
    }
    @Test void publicGetShowsEligibleAssignmentsAndNeverWrites() throws Exception {
        mvc.perform(get("/api/dispatch-attendance").param("month", "2026-10").param("projectId", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.project_id").value(1)).andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].assignment_mm").value(0.5))
            .andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty());
        unchanged(); verify(mapper, never()).lockProject(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"DAY", "HOUR"})
    void anonymousManualSaveAllowsMissingPriceAndZeroAttendance(String method) throws Exception {
        project = new Project("DP", method, "Y"); var n = item();
        if (method.equals("HOUR")) { n.remove("work_day_qty"); n.put("rglr_tm_qty",0); }
        else n.put("work_day_qty",0);
        save(n).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].price_available").value(false))
            .andExpect(jsonPath("$.data.items[0].ovtm_tm_qty").value(0));
        assertThat(rows).hasSize(1); assertThat(audits).hasSize(1);
        assertThat(audits.get(0).actor()).isNull();
        if (method.equals("HOUR")) assertThat(rows.get(20L).work_day_qty()).isNull();
        var order = inOrder(mapper);
        order.verify(mapper).lockProject(1); order.verify(mapper).monthStatus("2026-10");
        order.verify(mapper).lockAttendance(any()); order.verify(mapper).assignments(any());
        order.verify(mapper).confirmedAssignments(any()); order.verify(mapper).upsert(any());
    }
    @Test void mmRoundedHalfUpAndSourceMmComparisonDoesNotChangeSource() throws Exception {
        var n = item(); n.put("work_day_qty",1); n.put("mth_work_day_qty",8);
        save(n).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").value(0.13))
            .andExpect(jsonPath("$.data.items[0].assignment_mm").value(0.5));
        assertThat(assignments.get(0).assignment_mm()).isEqualByComparingTo("0.50");
        assertThat(audits.get(0).after()).contains("mth_work_day_qty","work_day_qty");
    }
    @Test void updateRetainsUnrelatedDeveloperAndRecordsEveryBeforeAndAfterValue() throws Exception {
        save(item()).andExpect(status().isOk());
        var other = item(); other.put("asgn_id",11); other.put("dev_id",21);
        save(other).andExpect(status().isOk()); var retained = rows.get(21L);
        var n = item(); n.put("work_day_qty",11); n.put("ovtm_tm_qty",2);
        save(n).andExpect(status().isOk());
        assertThat(rows).hasSize(2); assertThat(rows.get(21L)).isEqualTo(retained);
        assertThat(audits.get(2).type()).isEqualTo("UPDATE");
        assertThat(json.readTree(audits.get(2).before()).get("work_day_qty").decimalValue()).isEqualByComparingTo("10");
        assertThat(json.readTree(audits.get(2).after()).get("work_day_qty").decimalValue()).isEqualByComparingTo("11");
        assertThat(json.readTree(audits.get(2).after()).get("ovtm_tm_qty").intValue()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","32","99999","0.01","null","\"21\""})
    void invalidDenominatorIsField400(String value) throws Exception {
        var n = json.valueToTree(item()); ((com.fasterxml.jackson.databind.node.ObjectNode)n)
            .set("mth_work_day_qty",json.readTree(value));
        mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":["+n+"]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("items[0].mth_work_day_qty"))
            .andExpect(jsonPath("$.requestId").isNotEmpty()); unchanged();
    }
    @Test void missingDenominatorIsField400() throws Exception {
        var n=item(); n.remove("mth_work_day_qty");
        mvc.perform(put("/api/dispatch-attendance").param("month", "2026-10").param("projectId", "1")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("items", List.of(n)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("items[0].mth_work_day_qty")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"DAY","HOUR"})
    void modeSpecificRequiredQuantity(String method) throws Exception {
        project=new Project("DP",method,"Y"); var n=item(); n.remove("work_day_qty");
        save(n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("items[0]."+(method.equals("DAY")?"work_day_qty":"rglr_tm_qty"))); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"work_day_qty","rglr_tm_qty","ovtm_tm_qty"})
    void negativeQuantityRejected(String field) throws Exception {
        var n=item(); n.put(field,-1); save(n).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0]."+field)); unchanged();
    }
    @Test void closedMonthDoesNotChangeAnything() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        mvc.perform(put("/api/dispatch-attendance").param("month", "2026-10").param("projectId", "1")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("items", List.of(item())))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH")); unchanged();
    }
    @Test void confirmedUsageRejectsMutationAndRetainsOriginal() throws Exception {
        save(item()); var original=rows.get(20L); int count=audits.size(); clearInvocations(mapper);
        when(mapper.confirmedAssignments(any())).thenReturn(List.of(10L));
        var n=item(); n.put("work_day_qty",12);
        save(n).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED"));
        verify(mapper,never()).upsert(any()); assertThat(rows.get(20L)).isEqualTo(original); assertThat(audits).hasSize(count);
    }
    @Test void cannotReassignConfirmedAttendanceToAnotherAssignment() throws Exception {
        rows.put(20L,new Stored(1,99L,20,BigDecimal.ONE,null,null,BigDecimal.TEN));
        when(mapper.confirmedAssignments(any())).thenReturn(List.of(99L));
        save(item()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTENDANCE_BILLED"));
        verify(mapper,never()).upsert(any());
    }
    @Test void missingDayInHourModeKeepsMmNullWithWarning() throws Exception {
        project=new Project("DP","HOUR","Y"); var n=item(); n.remove("work_day_qty"); n.put("rglr_tm_qty",160);
        save(n).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].calculated_mm").isEmpty())
            .andExpect(jsonPath("$.data.items[0].warnings").isNotEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"asgn_id","dev_id"})
    void mismatchedAssociationIsRejected(String field) throws Exception {
        var n=item(); n.put(field,999); save(n).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("items[0].asgn_id")); unchanged();
    }
    @Test void duplicateDeveloperRejected() throws Exception {
        mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items",List.of(item(),item())))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("items[1].dev_id")); unchanged();
    }
    @Test void invalidSecondItemPreventsAnyWrite() throws Exception {
        var n=item(); n.put("asgn_id",999); n.put("dev_id",21);
        mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items",List.of(item(),n)))))
            .andExpect(status().isBadRequest()); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"GET","PUT"})
    void missingAndMalformedQueryFieldsUseApprovedError(String method) throws Exception {
        for (String query : List.of("?projectId=1","?month=2026-10","?month=2026-13&projectId=1",
                "?month=2026-10&projectId=x","?month=2026-10&projectId=0")) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),"/api/dispatch-attendance"+query)
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","{","{\"items\":null}","{\"items\":[]} {}"})
    void malformedBodyReturns400(String body) throws Exception {
        mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"TK","MO","IN"})
    void nonDispatchProjectCannotBeReadOrWritten(String type) throws Exception {
        project=new Project(type,"DAY","Y");
        mvc.perform(get(BASE)).andExpect(status().isBadRequest());
        save(item()).andExpect(status().isBadRequest()); unchanged();
    }
    @Test void dispatchMonthSettlementRejected() throws Exception {
        project=new Project("DP","MONTH","Y");
        mvc.perform(get(BASE)).andExpect(status().isBadRequest()); save(item()).andExpect(status().isBadRequest()); unchanged();
    }
    @Test void nonexistentProjectRejected() throws Exception {
        when(mapper.project(1)).thenReturn(null); when(mapper.lockProject(1)).thenReturn(null);
        mvc.perform(get(BASE)).andExpect(status().isBadRequest()); save(item()).andExpect(status().isBadRequest()); unchanged();
    }
    @Test void apiDoesNotRestrictPastYearWhenMonthIsOpen() throws Exception {
        mvc.perform(put("/api/dispatch-attendance?month=2025-10&projectId=1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("items",List.of(item()))))).andExpect(status().isOk());
    }
}
