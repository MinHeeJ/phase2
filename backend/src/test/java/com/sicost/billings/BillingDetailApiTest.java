package com.sicost.billings;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.*;
import static com.sicost.billings.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BillingController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingService.class, BillingAmountService.class,
    ApplicationSecurityConfiguration.class, CurrentUser.class, PermissionService.class, BillingDetailApiTest.Time.class})
class BillingDetailApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockBean BillingMapper mapper;
    @MockBean AccessPolicyMapper access;
    State current;
    List<DetailLine> lines;
    List<Audit> audits;
    @BeforeEach void setup() {
        var account = new AccessPolicyModels.Account(42L, "재무 사용자", null, "ACTIVE", null, false,
            LocalDateTime.of(2026, 10, 20, 9, 30));
        when(access.sessionOwner(any())).thenReturn(account);
        when(access.activeSession(any())).thenReturn(account);
        when(access.grants(any())).thenReturn(List.of(new AccessPolicyModels.Grant(3L, DataScope.ALL,
            true, false, false, true, "재무 담당")));
        when(access.parts(42L)).thenReturn(List.of());
        when(access.projects(42L)).thenReturn(List.of());
        String proof = AuthenticationProofs.randomSessionId(), csrf = AuthenticationProofs.randomSessionId();
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .addFilters(new RequestIdFilter())
            .defaultRequest(get("/").cookie(new jakarta.servlet.http.Cookie("JSESSIONID", proof),
                new jakarta.servlet.http.Cookie("XSRF-TOKEN", csrf)).header("X-CSRF-TOKEN", csrf)).build();
        when(mapper.projectTarget(1)).thenReturn(new AccessPolicyModels.Target(null, null, 10L, 1L));
        current = state("MO", "DRAFT", "2026-10", LocalDate.of(2026, 10, 10));
        lines = new ArrayList<>(List.of(line()));
        audits = new ArrayList<>();
        when(mapper.lockProject(1)).thenReturn(new Project(1, "MO", "MONTH", "Y"));
        when(mapper.state(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 99 ? null : current);
        when(mapper.lockBill(anyLong())).thenAnswer(i -> current);
        when(mapper.row(anyLong())).thenAnswer(i -> row(current));
        when(mapper.details(anyLong())).thenAnswer(i -> List.copyOf(lines));
        when(mapper.receipts(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.history(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.amendments(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.sources(any())).thenReturn(List.of(source()));
        when(mapper.vatRate(anyString())).thenReturn(new BigDecimal("7.5"));
        when(mapper.insert(any())).thenAnswer(i -> { current = i.getArgument(0, Write.class).state(); lines.clear(); return 8L; });
        when(mapper.update(any())).thenAnswer(i -> { current = i.getArgument(0, Write.class).state(); return 1; });
        doAnswer(i -> { lines.clear(); return null; }).when(mapper).deleteDetails(anyLong());
        doAnswer(i -> { lines.add(i.getArgument(0, DetailWrite.class).line()); return null; }).when(mapper).insertDetail(any());
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.existing(any())).thenReturn(null);
        when(mapper.list(any())).thenAnswer(i -> List.of(row(current)));
        when(mapper.count(any())).thenReturn(1L);
    }
    static State state(String type, String status, String month, LocalDate date) {
        return new State(7, 1, type.equals("TK") ? 3L : null, 1, month, date, 1000, 100,
            status, "N", null, null, null, null, null, LocalDate.of(2026, 10, 1), type);
    }
    static BillingRow row(State s) {
        return new BillingRow(s.bill_id(), s.bill_stg_id(), false, "B-2610-01", 1, "프로젝트", null,
            null, s.cntrct_ty_cd(), s.atrb_ym(), s.bill_dt(), s.sply_amt(), s.vat_amt(), s.sply_amt() + s.vat_amt(),
            s.bill_stts_cd(), s.txinv_no(), s.txinv_dt(), s.rcpt_plan_dt(), 0, 1100, 0, null, 1, "N", s.rmrk_desc());
    }
    static DetailLine line() {
        return new DetailLine(2L, 4L, 4L, "인력", "2026-10", new BigDecimal("0.5"),
            new BigDecimal("0.5"), 2000L, null, null, null, 0, 1000, null);
    }
    static Source source() {
        return new Source(2, 4, 4, "인력", "NEW", "2026-10", new BigDecimal("0.5"),
            2000L, null, null, null, null, null);
    }
    ResultActions postBody(String body) throws Exception {
        return mvc.perform(post("/api/billings").contentType("application/json").content(body));
    }
    ResultActions putBody(String body) throws Exception {
        return mvc.perform(put("/api/billings/7").contentType("application/json").content(body));
    }
    void noWrite() { verify(mapper, never()).insert(any()); verify(mapper, never()).update(any()); verify(mapper, never()).audit(any()); }
    @Test void authenticatedCreateStoresDraftAndEffectiveVatWithCurrentActor() throws Exception {
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.billing.vat_amt").value(75))
            .andExpect(jsonPath("$.data.details[0].bill_amt").value(1000));
        var order = inOrder(mapper);
        order.verify(mapper).lockClosingSources();
        order.verify(mapper).numberLock("B-2610-"); order.verify(mapper).lockProject(1);
        order.verify(mapper).insert(argThat(w -> w.number().equals("B-2610-01") && Long.valueOf(42L).equals(w.actor())));
        order.verify(mapper).insertDetail(any());
        assertThat(audits).hasSize(2); assertThat(audits.get(0).actor()).isEqualTo(42L);
    }
    @Test void turnkeyCreateUsesStageWithoutFabricatedAssignmentLine() throws Exception {
        when(mapper.lockProject(1)).thenReturn(new Project(1, "TK", "STAGE", "Y"));
        when(mapper.stage(any())).thenReturn(new Stage(3, 1, 2, 5000, LocalDate.of(2026, 10, 1)));
        postBody("{\"project_id\":1,\"stage_id\":3}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.sply_amt").value(5000)).andExpect(jsonPath("$.data.details").isEmpty());
        verify(mapper, never()).insertDetail(any());
    }
    @Test void internalProjectRejectedBeforeInsert() throws Exception {
        when(mapper.lockProject(1)).thenReturn(new Project(1, "IN", null, "Y"));
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrite();
    }
    @Test void invalidStageHasFieldError() throws Exception {
        when(mapper.lockProject(1)).thenReturn(new Project(1, "TK", "STAGE", "Y"));
        postBody("{\"project_id\":1,\"stage_id\":999}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("stage_id")); noWrite();
    }
    @Test void missingMonthHasFieldError() throws Exception {
        postBody("{\"project_id\":1}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month")); noWrite();
    }
    @Test void duplicateMonthExplainsExistingBilling() throws Exception {
        when(mapper.existing(any())).thenReturn(7L);
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ALREADY_BILLED")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("7"))); noWrite();
    }
    @Test void duplicateStageExcludesAdditionalEscape() throws Exception {
        when(mapper.lockProject(1)).thenReturn(new Project(1, "TK", "STAGE", "Y"));
        when(mapper.stage(any())).thenReturn(new Stage(3, 1, 1, 5000, LocalDate.of(2026, 10, 1)));
        when(mapper.existing(any())).thenReturn(7L);
        postBody("{\"project_id\":1,\"stage_id\":3}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_BILLED")); noWrite();
    }
    @Test void explicitAdditionalRequiresIncreasingOrdinal() throws Exception {
        when(mapper.existing(any())).thenReturn(7L); when(mapper.maximumOrdinal(any())).thenReturn(1);
        postBody("{\"project_id\":1,\"month\":\"2026-10\",\"additional\":true,\"bill_odr_no\":2}").andExpect(status().isOk());
        verify(mapper).insert(argThat(w -> w.state().bill_odr_no() == 2));
    }
    @ParameterizedTest @ValueSource(strings = {"", ",\"bill_odr_no\":1"})
    void additionalWithoutHigherOrdinalFails(String suffix) throws Exception {
        when(mapper.maximumOrdinal(any())).thenReturn(1);
        postBody("{\"project_id\":1,\"month\":\"2026-10\",\"additional\":true" + suffix + "}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bill_odr_no")); noWrite();
    }
    @ParameterizedTest @CsvSource({"sply_amt,-1", "vat_amt,-1", "sply_amt,1.5", "vat_amt,9223372036854775808"})
    void invalidCreateMoneyNeverWrites(String key, String value) throws Exception {
        postBody("{\"project_id\":1,\"month\":\"2026-10\",\"" + key + "\":" + value + "}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrite();
    }
    @Test void missingPriceBlocksEvenManualSupply() throws Exception {
        var s = source(); when(mapper.sources(any())).thenReturn(List.of(new Source(s.asgn_id(), s.dev_id(), s.cntrct_dev_id(), s.display_name(),
            "NEW", s.work_ym(), s.mm_qty(), null, null, null, null, null, null)));
        postBody("{\"project_id\":1,\"month\":\"2026-10\",\"sply_amt\":1000}").andExpect(status().isBadRequest()); noWrite();
    }
    @Test void missingAttendanceWarnsAndExcludesSource() throws Exception {
        when(mapper.lockProject(1)).thenReturn(new Project(1, "DP", "DAY", "Y"));
        postBody("{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details").isEmpty()).andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("근태 미입력")));
    }
    @Test void authenticatedUpdatePreservesManualVatAndSnapshotWithoutRecalculation() throws Exception {
        putBody("{\"sply_amt\":1234,\"vat_amt\":17,\"bill_dt\":\"2026-11-01\",\"txinv_no\":\"기록\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.sply_amt").value(1234)).andExpect(jsonPath("$.data.billing.vat_amt").value(17))
            .andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-10")).andExpect(jsonPath("$.data.details[0].work_ym").value("2026-10"))
            .andExpect(jsonPath("$.data.warnings", org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("귀속월과 다릅니다"))));
        verify(mapper, never()).sources(any()); verify(mapper, never()).deleteDetails(anyLong());
        assertThat(audits.get(0).before()).contains("1000"); assertThat(audits.get(0).after()).contains("1234", "기록");
    }
    @Test void explicitRecalculateRefreshesDetailButPreservesManualVat() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(new Source(2, 4, 4, "인력", "NEW", "2026-10", BigDecimal.ONE,
            3000L, null, null, null, null, null)));
        putBody("{\"recalculate\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.data.details[0].bill_amt").value(3000))
            .andExpect(jsonPath("$.data.billing.vat_amt").value(100)); verify(mapper).deleteDetails(7);
    }
    @ParameterizedTest @ValueSource(strings = {"CONFIRM", "CANCEL", "PLAN"})
    void nonDraftPreservesOriginalAndDetails(String status) throws Exception {
        current = state("MO", status, "2026-10", LocalDate.of(2026, 10, 1));
        putBody("{\"sply_amt\":2000}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL")); noWrite();
        assertThat(current.sply_amt()).isEqualTo(1000); assertThat(lines).containsExactly(line());
    }
    @Test void closedCreateNeverWrites() throws Exception {
        postBody("{\"project_id\":1,\"month\":\"2026-09\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH")); noWrite();
        var order = inOrder(mapper);
        order.verify(mapper).lockClosingSources();
        order.verify(mapper).lockProject(1);
        order.verify(mapper).monthStatus("2026-09");
    }
    @Test void updateAcquiresClosingGuardBeforeAnyStatusReadOrDomainLock() throws Exception {
        putBody("{\"vat_amt\":17}").andExpect(status().isOk());
        var order = inOrder(mapper);
        order.verify(mapper).lockClosingSources();
        order.verify(mapper).state(7);
        order.verify(mapper).lockProject(1);
        order.verify(mapper, times(2)).lockBill(7);
        order.verify(mapper).monthStatus("2026-10");
    }
    @Test void listDoesNotAcquireWriteGuardOrCreateMonthState() throws Exception {
        mvc.perform(get("/api/billings")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items").isArray());
        verify(mapper, never()).lockClosingSources();
        verify(mapper, never()).monthStatus(anyString());
        noWrite();
    }
    @Test void closedOriginalCannotMoveToOpenDate() throws Exception {
        current = state("TK", "DRAFT", null, LocalDate.of(2026, 9, 1));
        when(mapper.lockProject(1)).thenReturn(new Project(1, "TK", "STAGE", "Y"));
        putBody("{\"bill_dt\":\"2026-10-01\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); noWrite();
    }
    @Test void closedTargetDateCannotReplaceOpenDate() throws Exception {
        current = state("TK", "DRAFT", null, LocalDate.of(2026, 10, 1));
        when(mapper.lockProject(1)).thenReturn(new Project(1, "TK", "STAGE", "Y"));
        putBody("{\"bill_dt\":\"2026-09-01\"}").andExpect(status().isConflict()); noWrite();
    }
    @ParameterizedTest @ValueSource(strings = {"{\"bill_dt\":\"2026-02-30\"}", "{\"vat_amt\":-1}", "{\"sply_amt\":-2}", "{\"sply_amt\":1.5}", "{\"tot_amt\":3}", "{\"project_id\":2}", "{\"month\":\"2026-11\"}"})
    void invalidUpdateBoundaryIs400(String body) throws Exception {
        putBody(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrite();
    }
    @Test void lateConditionalWriteReturnsStale() throws Exception {
        doReturn(0).when(mapper).update(any());
        putBody("{\"sply_amt\":2000}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        verify(mapper, never()).audit(any());
    }
    @Test void manualOvertimeUpdatesOnlySnapshotAmountsWithoutSourceRecalculation() throws Exception {
        when(mapper.lockProject(1)).thenReturn(new Project(1, "DP", "HOUR", "Y"));
        when(mapper.updateDetail(any())).thenReturn(1);
        putBody("{\"details\":[{\"asgn_id\":2,\"ovtm_amt\":17}]}").andExpect(status().isOk());
        verify(mapper).updateDetail(argThat(w -> w.line().bill_amt() == 1017 && w.line().untprc_amt() == 2000));
        verify(mapper, never()).sources(any()); verify(mapper, never()).deleteDetails(anyLong());
    }
    @Test void authenticatedListAndDetailRemainReadOnly() throws Exception {
        mvc.perform(get("/api/billings?year=2026&type=MO&q=프로젝트&size=20")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.page").value(0));
        mvc.perform(get("/api/billings/7")).andExpect(status().isOk()).andExpect(jsonPath("$.data.change_history").isArray()); noWrite();
    }
    @Test void missingBillingReturnsNotFound() throws Exception { mvc.perform(get("/api/billings/99")).andExpect(status().isNotFound()); noWrite(); }
    @Test void sortInjectionIsRejected() throws Exception {
        mvc.perform(get("/api/billings").param("sort", "bill_dt;DROP TABLE bill.bill,asc")).andExpect(status().isBadRequest()); noWrite();
    }
    @Test void invoiceNumberColumnUsesTheSameSortNameAsTheFrontend() throws Exception {
        mvc.perform(get("/api/billings").param("sort", "txinv_no,asc")).andExpect(status().isOk());
        verify(mapper).list(argThat(q -> q.orderColumn().equals("txinv_no") && q.direction().equals("ASC")));
        noWrite();
    }
    @Test void undatedTurnkeyAmendmentCanBeEditedUsingTheOriginalBillingMonth() throws Exception {
        var original = state("TK", "CONFIRM", null, LocalDate.of(2026, 10, 1));
        current = new State(8, 1, null, 1, null, null, -100, -10, "DRAFT", "Y", 7L,
            null, null, null, "정정", null, "TK");
        when(mapper.state(7)).thenReturn(original);
        when(mapper.lockProject(1)).thenReturn(new Project(1, "TK", "STAGE", "Y"));
        mvc.perform(put("/api/billings/8").contentType("application/json")
                .content("{\"bill_dt\":\"2026-10-02\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.bill_dt").value("2026-10-02"));
        verify(mapper).update(argThat(w -> w.state().orgnl_bill_id().equals(7L)));
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void anonymousCannotReachEitherHandler(String method) throws Exception {
        var anonymous = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
            .addFilters(new RequestIdFilter()).build();
        anonymous.perform((method.equals("GET") ? get("/api/billings/7") : put("/api/billings/7")
            .contentType("application/json").content("{}"))).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verify(mapper, never()).state(anyLong()); noWrite();
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void expiredOrRevokedSessionCannotReachEitherHandler(String method) throws Exception {
        when(access.sessionOwner(any())).thenReturn(null);
        request(method).andExpect(status().isUnauthorized());
        verify(mapper, never()).state(anyLong()); noWrite();
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void passwordChangeSessionCannotReachEitherHandler(String method) throws Exception {
        when(access.sessionOwner(any())).thenReturn(new AccessPolicyModels.Account(42L, "재무 사용자", null,
            "ACTIVE", null, true, LocalDateTime.of(2026,10,20,9,30)));
        request(method).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        verify(mapper, never()).state(anyLong()); noWrite();
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void missingMenuActionIsEnforcedBeforeAnyBusinessRead(String method) throws Exception {
        when(access.grants(any())).thenReturn(List.of());
        request(method).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(mapper, never()).state(anyLong()); noWrite();
    }
    @Test void executivesCanReadButCannotModifyEvenWithLegacyWriteGrant() throws Exception {
        when(access.grants(any())).thenReturn(List.of(grant(2L, DataScope.ALL, "경영진")));
        request("GET").andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.bill_id").value(7));
        request("PUT").andExpect(status().isForbidden()); noWrite();
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void projectScopeDeniesAnotherProjectWithoutReadingItsDetails(String method) throws Exception {
        when(access.grants(any())).thenReturn(List.of(grant(3L, DataScope.PROJECT, "재무 담당")));
        when(access.projects(42L)).thenReturn(List.of(2L));
        request(method).andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
        verify(mapper, never()).row(anyLong()); verify(mapper, never()).details(anyLong()); noWrite();
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void unionOfAuthorizedRoleScopesIncludesTheBroaderRole(String method) throws Exception {
        when(access.grants(any())).thenReturn(List.of(grant(3L, DataScope.PROJECT, "재무 담당"),
            grant(1L, DataScope.PART, "시스템 관리자")));
        when(access.projects(42L)).thenReturn(List.of(2L)); when(access.parts(42L)).thenReturn(List.of(10L));
        request(method).andExpect(status().isOk());
    }
    @Test void readScopeFromAnotherRoleCannotBeCombinedWithWriteAction() throws Exception {
        when(access.grants(any())).thenAnswer(i -> {
            var q = i.getArgument(0, AccessPolicyModels.PermissionLookup.class);
            return q.action().equals("read") ? List.of(grant(1L, DataScope.ALL, "시스템 관리자"))
                : List.of(grant(3L, DataScope.PART, "재무 담당"));
        });
        when(access.parts(42L)).thenReturn(List.of(20L));
        request("GET").andExpect(status().isOk());
        request("PUT").andExpect(status().isNotFound()); noWrite();
    }
    @Test void partRelationshipIsRecheckedAfterLockingProject() throws Exception {
        when(access.grants(any())).thenReturn(List.of(grant(3L, DataScope.PART, "재무 담당")));
        when(access.parts(42L)).thenReturn(List.of(10L));
        when(mapper.projectTarget(1)).thenReturn(new AccessPolicyModels.Target(null,null,10L,1L),
            new AccessPolicyModels.Target(null,null,20L,1L));
        request("PUT").andExpect(status().isNotFound()); noWrite();
    }
    @Test void missingUpdateHasNoMutationOrHistory() throws Exception {
        mvc.perform(put("/api/billings/99").contentType("application/json").content("{}"))
            .andExpect(status().isNotFound()); noWrite();
    }
    @Test void authenticatedUpdateWithoutCsrfNeverReachesBusinessMapper() throws Exception {
        var protectedMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        protectedMvc.perform(put("/api/billings/7")
            .cookie(new jakarta.servlet.http.Cookie("JSESSIONID", AuthenticationProofs.randomSessionId()))
            .contentType("application/json").content("{\"vat_amt\":17}"))
            .andExpect(status().isForbidden());
        verify(mapper, never()).state(anyLong()); noWrite();
    }
    @Test void updateAuditsEveryChangedHeaderValueWithAuthenticatedActor() throws Exception {
        putBody("{\"bill_dt\":\"2026-10-21\",\"sply_amt\":1234,\"vat_amt\":17,\"txinv_no\":\"발행번호\",\"txinv_dt\":\"2026-10-22\",\"rcpt_plan_dt\":\"2026-11-01\",\"rmrk_desc\":\"변경\"}")
            .andExpect(status().isOk());
        assertThat(audits).hasSize(1);
        var audit = audits.get(0);
        assertThat(audit.actor()).isEqualTo(42L);
        var before = json.readTree(audit.before()); var after = json.readTree(audit.after());
        for (String field : List.of("bill_dt","sply_amt","vat_amt","txinv_no","txinv_dt","rcpt_plan_dt","rmrk_desc"))
            assertThat(after.get(field)).isNotEqualTo(before.get(field));
        assertThat(after.get("atrb_ym").asText()).isEqualTo("2026-10");
    }
    ResultActions request(String method) throws Exception {
        return method.equals("GET") ? mvc.perform(get("/api/billings/7")) : putBody("{\"vat_amt\":17}");
    }
    static AccessPolicyModels.Grant grant(Long id, DataScope scope, String name) {
        return new AccessPolicyModels.Grant(id, scope, true, false, id == 1L, true, name);
    }
}
