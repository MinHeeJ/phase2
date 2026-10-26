package com.sicost.contractchanges;

import static com.sicost.contractchanges.ProjectContractChangeModels.*;
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

@WebMvcTest(controllers = ProjectContractChangeController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectContractChangeService.class,
    CurrentUser.class, PermissionService.class, ProjectContractChangeApiTest.Time.class})
class ProjectContractChangeApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectContractChangeMapper mapper;
    ProjectState project;
    final Map<Long, ChangeRow> rows = new LinkedHashMap<>();
    final List<ChangeAudit> audits = new ArrayList<>();
    long sequence;
    static final String BASE = "/api/projects/1/contract-changes";
    @BeforeEach void setup() {
        project = state("TK", "Y"); rows.clear(); audits.clear(); sequence = 0;
        when(mapper.project(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? project : null);
        when(mapper.lockProject(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? project : null);
        when(mapper.list(anyLong())).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(mapper.row(any())).thenAnswer(i -> {
            var key = i.getArgument(0, ChangeKey.class); return key.id() == 1 ? rows.get(key.chgId()) : null;
        });
        when(mapper.insert(any())).thenAnswer(i -> {
            var w = i.getArgument(0, ChangeWrite.class); long key = ++sequence;
            var r = w.row(); rows.put(key, new ChangeRow(key, r.cntrct_chg_ty_cd(), r.cntrct_chg_stts_cd(),
                r.chg_dt(), r.bfr_cntrct_amt(), r.aft_cntrct_amt(), r.bfr_end_dt(), r.aft_end_dt(),
                r.incdec_mm_qty(), r.free_mm_qty(), r.rsn_desc(), r.realoc_need_yn(), r.cnfrm_user_id(), r.cnfrm_at()));
            return key;
        });
        doAnswer(i -> { var w = i.getArgument(0, ChangeWrite.class); rows.put(w.row().chgId(), w.row()); return null; })
            .when(mapper).update(any());
        doAnswer(i -> { rows.remove(i.getArgument(0, ChangeKey.class).chgId()); return null; }).when(mapper).delete(any());
        doAnswer(i -> {
            var w = i.getArgument(0, CurrentWrite.class);
            project = new ProjectState(1, project.cntrct_ty_cd(), project.use_yn(), project.cntrct_bgn_dt(),
                project.cntrct_end_dt(), project.prfm_bgn_dt(), w.end(), project.init_cntrct_amt(), w.amount(), w.mm());
            return null;
        }).when(mapper).current(any());
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    ProjectState state(String type, String enabled) {
        return new ProjectState(1, type, enabled, LocalDate.of(2026,1,1), LocalDate.of(2026,12,31),
            null, LocalDate.of(2026,12,31), 500000000L, 500000000L, BigDecimal.ZERO);
    }
    Map<String,Object> input(String type, String status) {
        var n = new LinkedHashMap<String,Object>();
        n.put("cntrct_chg_ty_cd",type); n.put("cntrct_chg_stts_cd",status);
        n.put("chg_dt","2026-10-23"); n.put("rsn_desc","계약 범위 변경");
        if (type.equals("ADD") || type.equals("CUT")) n.put("amount",100000000L);
        if (type.equals("TERM")) n.put("aft_end_dt","2027-03-31");
        if (type.equals("FREE")) n.put("free_mm_qty",new BigDecimal("2.50"));
        return n;
    }
    ResultActions save(String method, String path, Map<String,Object> input) throws Exception {
        return mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),path)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }
    void unchanged() { verify(mapper,never()).insert(any()); verify(mapper,never()).update(any());
        verify(mapper,never()).delete(any()); verify(mapper,never()).current(any()); assertThat(audits).isEmpty(); }
    @Test void anonymousListInitialContractAndReadOnly() throws Exception {
        mvc.perform(get("/api/projects/1/contract-changes")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.cntrct_bgn_dt").value("2026-01-01"))
            .andExpect(jsonPath("$.data.init_cntrct_amt").value(500000000)).andExpect(jsonPath("$.data.items").isArray());
        unchanged();
    }
    @Test void authHappyCreatePlanHasAuditSideEffectWithoutCurrentChange() throws Exception {
        mvc.perform(post("/api/projects/1/contract-changes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input("ADD", "PLAN"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.chgId").value(1))
            .andExpect(jsonPath("$.data.cntrct_chg_stts_cd").value("PLAN"))
            .andExpect(jsonPath("$.data.cnfrm_at").isEmpty())
            .andExpect(jsonPath("$.data.cnfrm_user_id").isEmpty());
        verify(mapper).insert(argThat(w -> w.id() == 1 && w.actor() == null
            && w.row().cntrct_chg_stts_cd().equals("PLAN")));
        assertThat(project.cur_cntrct_amt()).isEqualTo(500000000L);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).type()).isEqualTo("INSERT");
        verify(mapper, never()).current(any());
    }
    @Test void authHappyPutPlanToConfirmUpdatesCurrentAndAuditSideEffect() throws Exception {
        save("POST", BASE, input("ADD", "PLAN"));
        mvc.perform(put("/api/projects/1/contract-changes/1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input("ADD", "CONFIRM"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.cntrct_chg_stts_cd").value("CONFIRM"))
            .andExpect(jsonPath("$.data.bfr_cntrct_amt").value(500000000))
            .andExpect(jsonPath("$.data.aft_cntrct_amt").value(600000000))
            .andExpect(jsonPath("$.data.cnfrm_at").value("2026-10-23T00:01:00"))
            .andExpect(jsonPath("$.data.cnfrm_user_id").isEmpty())
            .andExpect(jsonPath("$.data.realoc_need_yn").value("Y"));
        verify(mapper).update(argThat(w -> w.row().chgId() == 1
            && w.row().cntrct_chg_stts_cd().equals("CONFIRM")));
        assertThat(project.cur_cntrct_amt()).isEqualTo(600000000L);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(1).before()).contains("PLAN");
        assertThat(audits.get(1).after()).contains("CONFIRM");
    }
    @Test void authHappyConfirmPlanUpdatesCurrentAndAuditSideEffect() throws Exception {
        save("POST", BASE, input("ADD", "PLAN"));
        mvc.perform(post("/api/projects/1/contract-changes/1/confirm"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.cntrct_chg_stts_cd").value("CONFIRM"))
            .andExpect(jsonPath("$.data.bfr_cntrct_amt").value(500000000))
            .andExpect(jsonPath("$.data.aft_cntrct_amt").value(600000000))
            .andExpect(jsonPath("$.data.cnfrm_at").value("2026-10-23T00:01:00"))
            .andExpect(jsonPath("$.data.cnfrm_user_id").isEmpty());
        assertThat(project.cur_cntrct_amt()).isEqualTo(600000000L);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(1).before()).contains("PLAN");
        assertThat(audits.get(1).after()).contains("CONFIRM");
        verify(mapper).current(argThat(w -> w.id() == 1 && w.amount() == 600000000L));
    }
    @Test void createValidationBlankReasonHasNoSideEffects() throws Exception {
        var body = input("ADD", "PLAN"); body.put("rsn_desc", " ");
        mvc.perform(post("/api/projects/1/contract-changes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value("rsn_desc"))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());
        unchanged();
    }
    @Test void putValidationBlankReasonHasNoSideEffects() throws Exception {
        var body = input("ADD", "PLAN"); body.put("rsn_desc", " ");
        mvc.perform(put("/api/projects/1/contract-changes/1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("rsn_desc")).andExpect(jsonPath("$.requestId").isNotEmpty());
        unchanged();
    }
    @Test void putBusinessConfirmedChangeIsImmutable() throws Exception {
        save("POST", BASE, input("ADD", "CONFIRM")); clearInvocations(mapper); audits.clear();
        mvc.perform(put("/api/projects/1/contract-changes/1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input("ADD", "PLAN")))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CHANGE_CONFIRMED"));
        unchanged(); assertThat(rows.get(1L).cntrct_chg_stts_cd()).isEqualTo("CONFIRM");
    }
    @Test void confirmValidationZeroChangeIdHasNoSideEffects() throws Exception {
        mvc.perform(post("/api/projects/1/contract-changes/0/confirm")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("chgId")).andExpect(jsonPath("$.requestId").isNotEmpty());
        unchanged();
    }
    @Test void confirmBusinessCutToZeroPreservesPlanAndCurrent() throws Exception {
        var body = input("CUT", "PLAN"); body.put("amount", 500000000L);
        save("POST", BASE, body); clearInvocations(mapper); audits.clear();
        mvc.perform(post("/api/projects/1/contract-changes/1/confirm")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("감액 뒤 계약금액이 0원 이하가 됩니다."));
        unchanged(); assertThat(rows.get(1L).cntrct_chg_stts_cd()).isEqualTo("PLAN");
        assertThat(project.cur_cntrct_amt()).isEqualTo(500000000L);
    }
    @Test void deleteValidationZeroChangeIdHasNoSideEffects() throws Exception {
        mvc.perform(delete("/api/projects/1/contract-changes/0")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("chgId")).andExpect(jsonPath("$.requestId").isNotEmpty());
        unchanged();
    }
    @Test void deleteBusinessConfirmedChangeIsImmutable() throws Exception {
        save("POST", BASE, input("ADD", "CONFIRM")); clearInvocations(mapper); audits.clear();
        mvc.perform(delete("/api/projects/1/contract-changes/1")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CHANGE_CONFIRMED"));
        unchanged(); assertThat(rows.get(1L).cntrct_chg_stts_cd()).isEqualTo("CONFIRM");
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","CONFIRM"})
    void anonymousConfirmThroughEveryEntry(String method) throws Exception {
        if (!method.equals("POST")) save("POST",BASE,input("ADD","PLAN")).andExpect(status().isOk());
        var n = input("ADD","CONFIRM"); n.put("incdec_mm_qty",new BigDecimal("1.25"));
        ResultActions result = method.equals("CONFIRM") ? mvc.perform(post(BASE+"/1/confirm"))
            : save(method,method.equals("POST")?BASE:BASE+"/1",n);
        result.andExpect(status().isOk()).andExpect(jsonPath("$.data.bfr_cntrct_amt").value(500000000))
            .andExpect(jsonPath("$.data.aft_cntrct_amt").value(600000000))
            .andExpect(jsonPath("$.data.cnfrm_at").value("2026-10-23T00:01:00"))
            .andExpect(jsonPath("$.data.realoc_need_yn").value("Y"));
        assertThat(project.cur_cntrct_amt()).isEqualTo(600000000L);
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.items[0].cntrct_chg_stts_cd").value("CONFIRM"));
        assertThat(audits).hasSize(method.equals("POST")?1:2);
        assertThat(audits.get(audits.size()-1).actor()).isNull();
        var order = inOrder(mapper); order.verify(mapper).lockProject(1);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void planSavesAndUpdatesNeverMutateCurrent(String method) throws Exception {
        if (method.equals("PUT")) save("POST",BASE,input("ADD","PLAN"));
        save(method,method.equals("POST")?BASE:BASE+"/1",input("TERM","PLAN")).andExpect(status().isOk());
        assertThat(project).isEqualTo(state("TK","Y")); verify(mapper,never()).current(any());
    }
    @Test void anonymousDeletePlanReadbackAndOneAudit() throws Exception {
        save("POST",BASE,input("ADD","PLAN")); audits.clear();
        mvc.perform(delete("/api/projects/1/contract-changes/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.chgId").value(1))
            .andExpect(jsonPath("$.data.prj_id").value(1));
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.items").isEmpty());
        assertThat(project).isEqualTo(state("TK","Y")); assertThat(audits).hasSize(1);
        assertThat(audits.get(0).type()).isEqualTo("DELETE");
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void confirmedRowsImmutable(String method) throws Exception {
        save("POST",BASE,input("ADD","CONFIRM")); var before = rows.get(1L); int count=audits.size();
        var result = method.equals("PUT") ? save(method,BASE+"/1",input("ADD","PLAN")) : mvc.perform(delete(BASE+"/1"));
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANGE_CONFIRMED"));
        assertThat(rows.get(1L)).isEqualTo(before); assertThat(audits).hasSize(count);
        assertThat(project.cur_cntrct_amt()).isEqualTo(600000000L);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","CONFIRM"})
    void cutZeroRejectedBeforeWriting(String method) throws Exception {
        var n=input("CUT","PLAN"); n.put("amount",500000000);
        if (!method.equals("POST")) save("POST",BASE,n);
        int count=audits.size(); n.put("cntrct_chg_stts_cd","CONFIRM");
        var result = method.equals("CONFIRM") ? mvc.perform(post(BASE+"/1/confirm"))
            : save(method,method.equals("POST")?BASE:BASE+"/1",n);
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("감액 뒤 계약금액이 0원 이하가 됩니다."));
        assertThat(project).isEqualTo(state("TK","Y")); assertThat(audits).hasSize(count);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","CONFIRM"})
    void termConfirmationUsesCurrentEnd(String method) throws Exception {
        if (!method.equals("POST")) save("POST",BASE,input("TERM","PLAN"));
        var result = method.equals("CONFIRM") ? mvc.perform(post(BASE+"/1/confirm"))
            : save(method,method.equals("POST")?BASE:BASE+"/1",input("TERM","CONFIRM"));
        result.andExpect(status().isOk()).andExpect(jsonPath("$.data.bfr_end_dt").value("2026-12-31"));
        assertThat(project.cur_end_dt()).isEqualTo(LocalDate.of(2027,3,31));
        assertThat(project.cur_cntrct_amt()).isEqualTo(500000000L);
    }
    @Test void repeatedConfirmationDoesNotIncrementOrAuditAgain() throws Exception {
        save("POST",BASE,input("ADD","PLAN")); mvc.perform(post(BASE+"/1/confirm")); int count=audits.size();
        mvc.perform(post(BASE+"/1/confirm")).andExpect(status().isOk());
        assertThat(project.cur_cntrct_amt()).isEqualTo(600000000L); assertThat(audits).hasSize(count);
    }
    @Test void plannedDeltaAppliesToLatestAmountNotStoredResult() throws Exception {
        save("POST",BASE,input("ADD","PLAN")); save("POST",BASE,input("ADD","CONFIRM"));
        mvc.perform(post(BASE+"/1/confirm")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bfr_cntrct_amt").value(600000000))
            .andExpect(jsonPath("$.data.aft_cntrct_amt").value(700000000));
    }
    @Test void freeConfirmationHasNoCurrentOrReallocationSideEffect() throws Exception {
        save("POST",BASE,input("FREE","CONFIRM")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.realoc_need_yn").value("N")).andExpect(jsonPath("$.data.free_mm_qty").value(2.5));
        verify(mapper,never()).current(any()); assertThat(project).isEqualTo(state("TK","Y"));
    }
    @Test void signedCutMmIsAddedWithoutAutomaticNegation() throws Exception {
        var n=input("CUT","CONFIRM"); n.put("incdec_mm_qty",new BigDecimal("-1.25"));
        save("POST",BASE,n).andExpect(status().isOk()); assertThat(project.cntrct_mm_qty()).isEqualByComparingTo("-1.25");
    }
    @ParameterizedTest @ValueSource(strings={"rsn_desc","amount","chg_dt","cntrct_chg_ty_cd","cntrct_chg_stts_cd"})
    void requiredFieldsDoNotWrite(String field) throws Exception {
        var n=input("ADD","PLAN"); n.remove(field);
        save("POST",BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void blankReasonAndMissingAmountAreDistinctValidationRules(String method) throws Exception {
        if (method.equals("PUT")) { save("POST",BASE,input("ADD","PLAN")); clearInvocations(mapper); audits.clear(); }
        var n=input("ADD","PLAN"); n.put("rsn_desc","  ");
        save(method,method.equals("POST")?BASE:BASE+"/1",n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rsn_desc"));
        n=input("ADD","PLAN"); n.remove("amount");
        save(method,method.equals("POST")?BASE:BASE+"/1",n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("amount")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","1.5","9223372036854775808","\"2\""})
    void invalidMoney(String value) throws Exception {
        var n=json.valueToTree(input("ADD","PLAN")); ((com.fasterxml.jackson.databind.node.ObjectNode)n).set("amount",json.readTree(value));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(n.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("amount")); unchanged();
    }
    @Test void termCannotEqualCurrentEnd() throws Exception {
        var n=input("TERM","PLAN"); n.put("aft_end_dt","2026-12-31");
        save("POST",BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aft_end_dt")); unchanged();
    }
    @Test void freeMmRequired() throws Exception {
        var n=input("FREE","PLAN"); n.remove("free_mm_qty");
        save("POST",BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("free_mm_qty")); unchanged();
    }
    @Test void mmPrecisionRejected() throws Exception {
        var n=input("ADD","PLAN"); n.put("incdec_mm_qty",new BigDecimal("1.001"));
        save("POST",BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("incdec_mm_qty")); unchanged();
    }
    @Test void nonTurnkeyCannotSetMm() throws Exception {
        project=state("MO","Y"); var n=input("ADD","PLAN"); n.put("incdec_mm_qty",1);
        save("POST",BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("incdec_mm_qty")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"ADD","CUT","FREE"})
    void internalOnlyAllowsTerm(String type) throws Exception {
        project=state("IN","Y"); save("POST",BASE,input(type,"PLAN"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cntrct_chg_ty_cd")); unchanged();
    }
    @Test void disabledProjectReadsButRejectsWrites() throws Exception {
        project=state("TK","N"); mvc.perform(get(BASE)).andExpect(status().isOk());
        save("POST",BASE,input("ADD","PLAN")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("use_yn")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE","CONFIRM"})
    void zeroChangeIdIs400WithoutMutation(String method) throws Exception {
        var r=method.equals("PUT")?save("PUT",BASE+"/0",input("ADD","PLAN"))
            : method.equals("DELETE")?mvc.perform(delete(BASE+"/0")):mvc.perform(post(BASE+"/0/confirm"));
        r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("chgId")); unchanged();
    }
    @Test void missingProjectIs404() throws Exception {
        mvc.perform(get("/api/projects/2/contract-changes")).andExpect(status().isNotFound()); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE","CONFIRM"})
    void missingOwnedChangeIs404(String method) throws Exception {
        var r=method.equals("PUT")?save("PUT",BASE+"/9",input("ADD","PLAN"))
            : method.equals("DELETE")?mvc.perform(delete(BASE+"/9")):mvc.perform(post(BASE+"/9/confirm"));
        r.andExpect(status().isNotFound()); unchanged();
    }
}
