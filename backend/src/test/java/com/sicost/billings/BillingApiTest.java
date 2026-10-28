package com.sicost.billings;

import static com.sicost.billings.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
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

@WebMvcTest(controllers = BillingController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingExceptionHandler.class,
    BillingService.class, BillingAmountService.class, CurrentUser.class, PermissionService.class,
    BillingApiTest.Time.class})
class BillingApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CurrentUser currentUser;
    @MockBean BillingMapper mapper;
    Project project;
    State state;
    List<Line> lines;
    List<Audit> audits;
    @BeforeEach void setup() {
        project = new Project(1, "TK", "MONTH", "Y");
        state = null;
        lines = new ArrayList<>();
        audits = new ArrayList<>();
        when(mapper.lockProject(1)).thenAnswer(i -> project);
        when(mapper.project(1)).thenAnswer(i -> project);
        when(mapper.stage(any())).thenAnswer(i -> i.getArgument(0, BillingModels.Scope.class).stageId() == 7
            ? new Stage(7, 1, "중도금", 1000, LocalDate.of(2026, 10, 1)) : null);
        when(mapper.nextNumber(anyString())).thenReturn(100L);
        when(mapper.active(any())).thenReturn(new ArrayList<>());
        when(mapper.sources(any())).thenReturn(new ArrayList<>());
        when(mapper.state(1)).thenAnswer(i -> state);
        when(mapper.lockBill(1)).thenAnswer(i -> state);
        when(mapper.details(1)).thenAnswer(i -> new ArrayList<>(lines));
        when(mapper.insert(any())).thenAnswer(i -> {
            State s = i.getArgument(0, Write.class).row();
            state = copy(s, "DRAFT");
            return 1L;
        });
        when(mapper.update(any())).thenAnswer(i -> { state = i.getArgument(0, Write.class).row(); return 1; });
        doAnswer(i -> { lines.add(i.getArgument(0, DetailWrite.class).line()); return null; }).when(mapper).insertDetail(any());
        doAnswer(i -> { lines.clear(); return null; }).when(mapper).deleteDetails(1);
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
        when(mapper.row(any())).thenAnswer(i -> row(state));
        when(mapper.receipts(1)).thenReturn(new ArrayList<>());
        when(mapper.amendments(any())).thenReturn(new ArrayList<>());
        when(mapper.history(1)).thenReturn(new ArrayList<>());
    }
    State copy(State s, String status) {
        return new State(1, s.bill_no(), s.prj_id(), s.bill_stg_id(), s.bill_odr_no(), s.atrb_ym(),
            s.bill_dt(), s.sply_amt(), s.vat_amt(), status, s.txinv_no(), s.txinv_dt(), s.rcpt_plan_dt(),
            s.rmrk_desc(), s.amnd_yn(), s.orgnl_bill_id(), s.created_date());
    }
    Row row(State s) {
        return new Row(1L, s.bill_stg_id(), false, s.bill_no(), 1, "계약 시험", null, null,
            project.contract_type(), s.atrb_ym(), s.bill_dt(), s.sply_amt(), s.vat_amt(),
            s.sply_amt()+s.vat_amt(), s.bill_stts_cd(), s.txinv_no(), s.txinv_dt(), s.rcpt_plan_dt(), 0,
            s.sply_amt()+s.vat_amt(), 0, null);
    }
    ResultActions postBody(String body) throws Exception {
        return mvc.perform(post("/api/billings").contentType(MediaType.APPLICATION_JSON).content(body));
    }
    ResultActions putBody(String body) throws Exception {
        return mvc.perform(put("/api/billings/1").contentType(MediaType.APPLICATION_JSON).content(body));
    }
    void create() throws Exception { postBody("{\"project_id\":1,\"stage_id\":7}").andExpect(status().isOk()); }
    void noWrites() { verify(mapper, never()).insert(any()); verify(mapper, never()).update(any()); assertThat(audits).isEmpty(); }
    @Test void anonymousTkCreateUsesGeneratedKeyBeforeDetailAndAudit() throws Exception {
        mvc.perform(post("/api/billings").contentType(MediaType.APPLICATION_JSON)
            .content("{\"project_id\":1,\"stage_id\":7}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("DRAFT"));
        mvc.perform(get("/api/billings/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.bill_no").value("B-2610-100"))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("DRAFT"))
            .andExpect(jsonPath("$.data.details[0].work_ym").value("2026-10"));
        var order = inOrder(mapper);
        order.verify(mapper).lockNumber("B-2610-"); order.verify(mapper).lockProject(1);
        order.verify(mapper).insert(any()); order.verify(mapper).insertDetail(argThat(w -> w.id() == 1));
        assertThat(audits).isNotEmpty().allMatch(a -> Objects.equals(a.actor(), currentUser.userId()));
    }
    @ParameterizedTest @ValueSource(strings={"MO","DP"})
    void anonymousMonthlyCreatePersistsSourceSnapshot(String type) throws Exception {
        project = new Project(1,type,"MONTH","Y");
        when(mapper.sources(any())).thenReturn(List.of(new Source(3,4,4L,"인력","NEW",
            new BigDecimal("0.75"),1001L,null,null,null,null,null)));
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details[0].bill_amt").value(750))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(750));
    }
    @Test void anonymousInternalProjectRejectedAsBusinessValidationNotAuth() throws Exception {
        project = new Project(1,"IN",null,"Y");
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"project_id\":1}","{\"project_id\":1,\"stage_id\":8}",
        "{\"project_id\":1,\"stage_id\":7,\"sply_amt\":1.1}","{\"project_id\":1,\"stage_id\":7,\"vat_amt\":-1}",
        "{\"project_id\":1,\"stage_id\":7,\"tot_amt\":1}","{\"project_id\":1,\"stage_id\":7,\"sply_amt\":9223372036854775808}"})
    void invalidCreateHasNoRows(String body) throws Exception {
        mvc.perform(post("/api/billings").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.requestId").isNotEmpty()); noWrites();
    }
    @Test void invalidMonthlyAttributionHasFieldError() throws Exception {
        project = new Project(1,"MO","MONTH","Y");
        postBody("{\"project_id\":1,\"month\":\"2026-13\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("month")); noWrites();
    }
    @Test void duplicateStagePointsToExistingBill() throws Exception {
        create(); when(mapper.active(any())).thenReturn(List.of(state)); clearInvocations(mapper); audits.clear();
        mvc.perform(post("/api/billings").contentType(MediaType.APPLICATION_JSON)
            .content("{\"project_id\":1,\"stage_id\":7}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ALREADY_BILLED")); noWrites();
    }
    @Test void monthlyDuplicateRequiresExplicitAdditionalAndLargerOrder() throws Exception {
        create(); when(mapper.active(any())).thenReturn(List.of(state));
        project = new Project(1,"MO","MONTH","Y"); clearInvocations(mapper); audits.clear();
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isConflict()); noWrites();
    }
    @Test void explicitAdditionalWithLargerOrderCreatesDraft() throws Exception {
        create(); when(mapper.active(any())).thenReturn(List.of(state)); project = new Project(1,"MO","MONTH","Y");
        postBody("{\"project_id\":1,\"month\":\"2026-10\",\"additional\":true,\"bill_odr_no\":2}")
            .andExpect(status().isOk()); assertThat(state.bill_odr_no()).isEqualTo(2);
    }
    @Test void ordinaryUpdatePreservesSnapshotAndOmittedFields() throws Exception {
        create(); var old = new ArrayList<>(lines); clearInvocations(mapper);
        mvc.perform(put("/api/billings/1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"sply_amt\":900,\"txinv_no\":\"invoice\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(900));
        assertThat(lines).isEqualTo(old); verify(mapper,never()).sources(any()); verify(mapper,never()).deleteDetails(anyLong());
        putBody("{\"rmrk_desc\":\"보존\"}").andExpect(status().isOk());
        assertThat(state.sply_amt()).isEqualTo(900); assertThat(state.txinv_no()).isEqualTo("invoice");
        assertThat(audits).anyMatch(a -> "sply_amt".equals(a.column()) && "1000".equals(a.before()) && "900".equals(a.after()));
        assertThat(audits).allMatch(a -> Objects.equals(a.actor(), currentUser.userId()));
    }
    @Test void explicitNullClearsOnlyNullableValues() throws Exception {
        create(); putBody("{\"bill_dt\":\"2026-10-15\",\"txinv_no\":\"inv\"}").andExpect(status().isOk());
        putBody("{\"bill_dt\":null,\"txinv_no\":null}").andExpect(status().isOk());
        assertThat(state.bill_dt()).isNull(); assertThat(state.txinv_no()).isNull();
    }
    @Test void explicitRecalculateRefreshesSourceButKeepsManualAmounts() throws Exception {
        create(); clearInvocations(mapper);
        putBody("{\"recalculate\":true,\"sply_amt\":800,\"vat_amt\":30}").andExpect(status().isOk());
        verify(mapper).deleteDetails(1); verify(mapper).insertDetail(any());
        assertThat(state.sply_amt()).isEqualTo(800); assertThat(state.vat_amt()).isEqualTo(30);
    }
    @ParameterizedTest @ValueSource(strings={"{\"bill_dt\":\"2026-02-30\"}","{\"sply_amt\":-1}","{\"vat_amt\":-1}",
        "{\"vat_amt\":null}","{\"sply_amt\":1.5}","{\"project_id\":2}","{\"month\":\"2026-11\"}",
        "{\"tot_amt\":1}","{\"details\":[{\"asgn_id\":99,\"ovtm_amt\":1}]}"})
    void invalidUpdatePreservesOriginal(String body) throws Exception {
        create(); var before = state; var detail = new ArrayList<>(lines); clearInvocations(mapper); audits.clear();
        mvc.perform(put("/api/billings/1").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(state).isEqualTo(before); assertThat(lines).isEqualTo(detail); noWrites();
    }
    @Test void confirmedUpdateIsConflictWithoutChangingAnything() throws Exception {
        create(); state = copy(state,"CONFIRM"); var before = state; clearInvocations(mapper); audits.clear();
        mvc.perform(put("/api/billings/1").contentType(MediaType.APPLICATION_JSON).content("{\"sply_amt\":10}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("STALE_BILL"));
        assertThat(state).isEqualTo(before); noWrites();
    }
    @Test void closedOldMonthPreventsUpdate() throws Exception {
        create(); when(mapper.monthStatus("2026-10")).thenReturn("CLOSED"); clearInvocations(mapper); audits.clear();
        putBody("{\"bill_dt\":\"2026-11-01\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH")); noWrites();
    }
    @Test void closedNewMonthPreventsUpdate() throws Exception {
        create(); when(mapper.monthStatus("2026-11")).thenReturn("CLOSED"); clearInvocations(mapper); audits.clear();
        putBody("{\"bill_dt\":\"2026-11-01\"}").andExpect(status().isConflict()); noWrites();
    }
    @Test void provisionalTkMonthBlocksCreate() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        postBody("{\"project_id\":1,\"stage_id\":7}").andExpect(status().isConflict()); noWrites();
    }
    @Test void missingPriceBlocksEvenIfAttendanceIsMissing() throws Exception {
        project = new Project(1,"DP","HOUR","Y");
        when(mapper.sources(any())).thenReturn(List.of(new Source(3,4,4L,"인력","NEW",BigDecimal.ONE,
            null,null,null,null,null,null)));
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("bill_untprc_amt")); noWrites();
    }
    @Test void missingAttendanceExcludedWithoutInventedAmount() throws Exception {
        project = new Project(1,"DP","DAY","Y");
        when(mapper.sources(any())).thenReturn(List.of(new Source(3,4,4L,"인력","NEW",BigDecimal.ONE,
            100L,null,null,null,null,null)));
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details").isEmpty());
    }
    @Test void unsupportedMoDayGenerationRejected() throws Exception {
        project = new Project(1,"MO","DAY","Y");
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isBadRequest()); noWrites();
    }
    @Test void listPassesSameFilterObjectToRowsAndCount() throws Exception {
        when(mapper.list(any())).thenReturn(new ArrayList<>()); when(mapper.count(any())).thenReturn(23L);
        mvc.perform(get("/api/billings?year=2026&status=CANCEL&type=MO&customerId=2&q=검색&page=1&size=20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(23))
            .andExpect(jsonPath("$.data.page").value(1));
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class); verify(mapper).list(captured.capture());
        verify(mapper).count(captured.getValue()); assertThat(captured.getValue().offset()).isEqualTo(20); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"sort=invalid,asc","sort=bill_dt,drop","size=30","page=-1","year=x"})
    void invalidQueryHasApprovedError(String query) throws Exception {
        mvc.perform(get("/api/billings?"+query)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrites();
    }
    @Test void malformedJsonAndIdHaveApprovedError() throws Exception {
        postBody("{").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/billings/not-an-id")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrites();
    }
    @Test void unknownBillIs404AndReadOnly() throws Exception {
        mvc.perform(get("/api/billings/9")).andExpect(status().isNotFound()); noWrites();
    }
}
