package com.sicost.changes;

import static com.sicost.changes.AssignmentChangeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.persistence.*;
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
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = AssignmentChangeController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, AssignmentChangeService.class,
    AssignmentConcurrencyService.class, CurrentUser.class, PermissionService.class, AssignmentChangeApiTest.Time.class})
class AssignmentChangeApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AssignmentChangeMapper mapper;
    @MockBean AssignmentConcurrencyMapper concurrency;
    static final String BASE = "/api/assignments/1/changes";
    static final LocalDateTime VERSION = LocalDateTime.parse("2026-10-13T12:00:00.123456");
    final Map<Long, ChangeRow> rows = new LinkedHashMap<>();
    final List<RateRow> rates = new ArrayList<>();
    State state;
    long sequence;
    @BeforeEach void setup() {
        state = state("ACTIVE", "MO", "RUN");
        rows.clear(); rates.clear(); sequence = 0;
        rates.add(new RateRow(LocalDate.parse("2026-09-01"), 7000000L));
        when(mapper.state(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? state : null);
        when(concurrency.lockProject(anyLong())).thenReturn(2L);
        when(concurrency.lockAssignment(anyLong())).thenReturn(new AssignmentConcurrencyMapper.AssignmentLock(1, VERSION, VERSION.plusDays(1)));
        when(concurrency.advance(any())).thenReturn(VERSION.plusDays(1));
        when(mapper.closedMonths()).thenReturn(List.of("2026-09"));
        when(mapper.rates(anyLong())).thenAnswer(i -> new ArrayList<>(rates));
        when(mapper.months(anyLong())).thenReturn(List.of(new MonthRow("2026-11", BigDecimal.ONE, new BigDecimal("0.50"), "Y", new BigDecimal("0.50"), "N")));
        when(mapper.codeName(any())).thenAnswer(i -> {
            var k = i.getArgument(0, CodeKey.class);
            return switch (k.group()) {
                case "ASGN_CHG_RSN" -> "CUST".equals(k.code()) ? "고객 요청" : null;
                case "ASSIGN_KIND" -> Map.of("NEW", "신규", "SUB", "대체투입", "ADD", "추가투입").get(k.code());
                case "EXT_POSSIBILITY" -> Map.of("H", "높음", "M", "보통", "L", "낮음").get(k.code());
                default -> null;
            };
        });
        when(mapper.developer(anyLong())).thenReturn(9L);
        when(mapper.list(any())).thenAnswer(i -> {
            var f = i.getArgument(0, ChangeFilter.class);
            return rows.values().stream().filter(r -> f.type() == null || f.type().equals(r.asgn_chg_ty_cd())).toList();
        });
        when(mapper.row(any())).thenAnswer(i -> {
            var k = i.getArgument(0, ChangeKey.class); return k.id() == 1 ? rows.get(k.chgId()) : null;
        });
        when(mapper.insert(any())).thenAnswer(i -> {
            var w = i.getArgument(0, HistoryWrite.class);
            long key = ++sequence;
            var target = w.target() == null ? null : rows.get(w.target());
            var r = new ChangeRow(key, 1, w.type(), w.application(), w.before(), w.after(), null,
                w.reason(), w.reason() == null ? null : "고객 요청", w.memo(), w.retro(), "N", w.target(),
                target == null ? null : target.aply_dt(), target == null ? null : target.asgn_chg_ty_cd(), null, null, VERSION);
            rows.put(key, r); return key;
        });
        when(mapper.markFixed(any())).thenAnswer(i -> {
            var k = i.getArgument(0, ChangeKey.class); var r = rows.get(k.chgId());
            rows.put(k.chgId(), new ChangeRow(r.asgn_chg_id(), r.asgn_id(), r.asgn_chg_ty_cd(), r.aply_dt(),
                r.bfr_val(), r.aft_val(), r.new_asgn_id(), r.rsn_cd(), r.rsn_nm(), r.rsn_desc(), r.retro_yn(), "Y",
                r.fix_trgt_chg_id(), r.fix_trgt_aply_dt(), r.fix_trgt_chg_ty_cd(), r.chg_user_id(), r.chg_user_nm(), r.chg_at()));
            return 1;
        });
        when(mapper.header(any())).thenAnswer(i -> new Header(1, "A-0001", 2, "프로젝트", 3, "개발자", "K-0003", "BE",
            state.bgn_dt(), state.end_dt(), LocalDate.parse("2026-12-31"), BigDecimal.ONE, state.asgn_se_cd(), "MONTH",
            state.ext_psblty_cd(), state.cntrct_dev_id(), null, state.asgn_stts_cd(), state.rplc_asgn_id(), 7000000L,
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, VERSION.plusDays(1)));
        doAnswer(i -> {
            var w = i.getArgument(0, PeriodWrite.class);
            state = new State(1, 2, 3, w.start(), w.end(), BigDecimal.ONE, w.kind(), w.contractPerson(), state.rplc_asgn_id(),
                state.ext_psblty_cd(), state.asgn_stts_cd(), state.cntrct_ty_cd(), state.use_yn(), state.prj_stts_cd(), "FT", null);
            return null;
        }).when(mapper).period(any());
    }
    State state(String status, String contract, String project) {
        return new State(1,2,3,LocalDate.parse("2026-09-01"),LocalDate.parse("2026-12-31"),BigDecimal.ONE,
            "NEW",null,null,"M",status,contract,"Y",project,"FT",null);
    }
    Map<String,Object> input(String type) {
        var n = new LinkedHashMap<String,Object>();
        n.put("updated_at", VERSION.toString()); n.put("asgn_chg_ty_cd", type); n.put("aply_dt", "2026-10-14");
        switch(type) {
            case "EXTEND" -> { n.put("aply_dt", "2027-01-01"); n.put("end_dt", "2027-03-31"); }
            case "EARLY_END" -> { n.put("aply_dt", "2026-11-15"); n.put("rsn_cd", "CUST"); }
            case "RATE" -> { n.put("aply_dt", "2026-12-01"); n.put("work_ym", "2026-12"); n.put("bill_untprc_amt", 7200000); }
            case "EXT_CHANCE" -> n.put("ext_psblty_cd", "H");
            case "PERIOD" -> n.put("asgn_se_cd", "ADD");
            default -> { }
        }
        return n;
    }
    Map<String,Object> correction() { return new LinkedHashMap<>(Map.of("updated_at", VERSION.toString(), "aft_val", "바른 내용", "rsn_cd", "CUST")); }
    ResultActions postBody(String path, Map<String,Object> body) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body)));
    }
    void append(String type) {
        var w = new HistoryWrite(1,type,LocalDate.parse("2026-10-14"),"전","후",null,null,"N",null,null);
        mapper.insert(w); clearInvocations(mapper);
    }
    void noBusinessWrites() {
        verify(mapper, never()).insert(any()); verify(mapper, never()).period(any());
        verify(mapper, never()).rate(any()); verify(mapper, never()).month(any()); verify(mapper, never()).markFixed(any());
    }
    @Test void anonymousListFiltersAndReturnsEmptyWithoutWrites() throws Exception {
        append("START"); append("EXTEND"); append("RATE");
        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get(BASE).param("type","EXTEND")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get(BASE).param("type","REPLACE")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        noBusinessWrites();
    }
    @Test void invalidFilterIsFieldError() throws Exception {
        mvc.perform(get("/api/assignments/1/changes").param("type","INVALID"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("type"))
            .andExpect(jsonPath("$.requestId").isNotEmpty()); noBusinessWrites();
    }
    @Test void unknownAssignmentIsNotFound() throws Exception {
        mvc.perform(get("/api/assignments/99/changes")).andExpect(status().isNotFound()); noBusinessWrites();
    }
    @ParameterizedTest @ValueSource(strings={"EXTEND","EARLY_END","RATE","EXT_CHANCE","PERIOD"})
    void anonymousSuccessfulTypedChangeHasConcreteResponse(String type) throws Exception {
        postBody(BASE,input(type)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.assignment.asgn_id").value(1)).andExpect(jsonPath("$.data.assignment.updated_at").isString())
            .andExpect(jsonPath("$.data.change.asgn_chg_ty_cd").value(type));
        verify(mapper).insert(argThat(w -> type.equals(w.type())));
    }
    @Test void extensionRebuildsOpenMonthsAndPreservesManual() throws Exception {
        mvc.perform(post("/api/assignments/1/changes").contentType("application/json")
            .content(json.writeValueAsBytes(input("EXTEND")))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignment.end_dt").value("2027-03-31"))
            .andExpect(jsonPath("$.data.change.bfr_val").value("2026-12-31"))
            .andExpect(jsonPath("$.data.change.aft_val").value("2027-03-31"));
        verify(mapper).month(argThat(m -> m.month().equals("2026-11") && m.quantity().compareTo(new BigDecimal("0.50")) == 0));
        for (String month : List.of("2027-01","2027-02","2027-03")) verify(mapper).month(argThat(m -> month.equals(m.month())));
        verify(mapper, never()).month(argThat(m -> m.month().equals("2026-09")));
    }
    @Test void earlyEndUsesDateWithoutInferredStatusTransition() throws Exception {
        postBody(BASE,input("EARLY_END")).andExpect(status().isOk()).andExpect(jsonPath("$.data.assignment.end_dt").value("2026-11-15"))
            .andExpect(jsonPath("$.data.assignment.asgn_stts_cd").value("ACTIVE"));
        verify(mapper).deleteOutsideMonths(1); verify(mapper,never()).month(argThat(m -> m.month().equals("2026-12")));
    }
    @ParameterizedTest @ValueSource(strings={"2026-12-31","2026-12-30"})
    void nonLaterExtensionReportsExactMessageAndNoBusinessWrites(String date) throws Exception {
        var n = input("EXTEND"); n.put("end_dt",date);
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("end_dt"))
            .andExpect(jsonPath("$.message").value("새 종료일은 현재 종료일(2026-12-31)보다 늦어야 합니다.")); noBusinessWrites();
    }
    @Test void earlyEndSameDateHasExactError() throws Exception {
        var n = input("EARLY_END"); n.put("aply_dt","2026-12-31");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("현재 종료일과 같습니다.")); noBusinessWrites();
    }
    @Test void turnkeyRateIsRejected() throws Exception {
        state=state("ACTIVE","TK","RUN"); postBody(BASE,input("RATE")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("bill_untprc_amt")); noBusinessWrites();
    }
    @Test void sameRateIsRejected() throws Exception {
        var n=input("RATE"); n.put("bill_untprc_amt",7000000);
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bill_untprc_amt")); noBusinessWrites();
    }
    @Test void rateMonthOutsidePeriodIsRejected() throws Exception {
        var n=input("RATE"); n.put("work_ym","2027-01");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("work_ym")); noBusinessWrites();
    }
    @Test void sameChanceIsRejected() throws Exception {
        var n=input("EXT_CHANCE"); n.put("ext_psblty_cd","M");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("현재 값과 같습니다.")); noBusinessWrites();
    }
    @Test void chanceClearRequiresPresentNullableField() throws Exception {
        var n=input("EXT_CHANCE"); n.put("ext_psblty_cd",null);
        postBody(BASE,n).andExpect(status().isOk()); verify(mapper).chance(new ChanceWrite(1,null));
    }
    @Test void chanceMissingFieldDoesNotMeanClear() throws Exception {
        var n=input("EXT_CHANCE"); n.remove("ext_psblty_cd");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ext_psblty_cd")); noBusinessWrites();
    }
    @Test void unchangedPeriodIsRejected() throws Exception {
        var n=input("PERIOD"); n.put("asgn_se_cd","NEW");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("바뀐 내용이 없습니다.")); noBusinessWrites();
    }
    @Test void periodEndCorrectionRequiresReason() throws Exception {
        var n=input("PERIOD"); n.put("end_dt","2027-01-31");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rsn_cd")); noBusinessWrites();
    }
    @Test void subTransitionRequiresContractPerson() throws Exception {
        var n=input("PERIOD"); n.put("asgn_se_cd","SUB");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cntrct_dev_id")); noBusinessWrites();
    }
    @Test void closedOriginalStartMonthPreventsPeriodShift() throws Exception {
        var n=input("PERIOD"); n.put("bgn_dt","2026-10-01");
        postBody(BASE,n).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); noBusinessWrites();
    }
    @Test void overlapConflictPreventsAllBusinessWrites() throws Exception {
        when(mapper.overlap(any())).thenReturn(1);
        postBody(BASE,input("EXTEND")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVERLAP_ASSIGNMENT")); noBusinessWrites();
    }
    @ParameterizedTest @ValueSource(strings={"END","LOST"})
    void closedProjectRejectsChanges(String status) throws Exception {
        state=state("ACTIVE","MO",status);
        postBody(BASE,input("EXTEND")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED")); noBusinessWrites();
    }
    @ParameterizedTest @ValueSource(strings={"CANCELLED","ENDED"})
    void readOnlyLegacyAndCancelledStatesHaveNoMutators(String status) throws Exception {
        state=state(status,"MO","RUN"); postBody(BASE,input("EXTEND")).andExpect(status().isConflict()); noBusinessWrites();
    }
    @ParameterizedTest @ValueSource(strings={"EXTEND","EARLY_END","EXT_CHANCE","PERIOD"})
    void replacedOriginalRejectsDisallowedChanges(String type) throws Exception {
        state=state("REPLACED","MO","RUN"); postBody(BASE,input(type)).andExpect(status().isConflict()); noBusinessWrites();
    }
    @Test void replacedOriginalPermitsRateWithinRemainingPeriod() throws Exception {
        state=state("REPLACED","MO","RUN"); postBody(BASE,input("RATE")).andExpect(status().isOk()); verify(mapper).rate(any());
    }
    @Test void staleTokenIsRejectedBeforeMutation() throws Exception {
        var n=input("EXTEND"); n.put("updated_at",VERSION.minusNanos(1000).toString());
        postBody(BASE,n).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ASSIGNMENT"))
            .andExpect(jsonPath("$.message").value("다른 사람이 먼저 바꿨습니다. 창을 다시 열어 주세요.")); noBusinessWrites();
    }
    @Test void precisionIsNotTruncatedOnWire() throws Exception {
        postBody(BASE,input("EXTEND")).andExpect(status().isOk()).andExpect(jsonPath("$.data.assignment.updated_at").value("2026-10-14T12:00:00.123456"));
    }
    @ParameterizedTest @CsvSource({"2026-10-13,Y","2026-10-14,N"})
    void seoulTodayDeterminesRetroFlag(String date,String expected) throws Exception {
        var n=input("EXT_CHANCE"); n.put("aply_dt",date); n.put("rsn_cd","CUST");
        postBody(BASE,n).andExpect(status().isOk()).andExpect(jsonPath("$.data.change.retro_yn").value(expected));
    }
    @ParameterizedTest @ValueSource(strings={"EXT_CHANCE","EARLY_END"})
    void mandatoryReasonCannotBeOmitted(String type) throws Exception {
        var n=input(type); n.put("aply_dt","2026-10-13"); n.remove("rsn_cd");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("사유를 골라 주세요.")); noBusinessWrites();
    }
    @Test void inactiveReasonIsRejected() throws Exception {
        var n=input("EXT_CHANCE"); n.put("rsn_cd","INACTIVE");
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rsn_cd")); noBusinessWrites();
    }
    @Test void memoOver200IsRejected() throws Exception {
        var n=input("EXT_CHANCE"); n.put("rsn_desc","가".repeat(201));
        postBody(BASE,n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rsn_desc")); noBusinessWrites();
    }
    @Test void closedRateShiftsFinancialDateButPreservesRequestedHistoryDate() throws Exception {
        var n=input("RATE"); n.put("work_ym","2026-09"); n.put("aply_dt","2026-09-01"); n.put("rsn_cd","CUST");
        postBody(BASE,n).andExpect(status().isOk()).andExpect(jsonPath("$.data.change.aply_dt").value("2026-09-01"));
        verify(mapper).rate(argThat(w -> w.effective().equals(LocalDate.parse("2026-10-01"))));
    }
    @Test void anonymousCorrectionMarksOnlyTargetAndAppendsFix() throws Exception {
        append("EXTEND");
        mvc.perform(post("/api/assignments/1/changes/1/fix").contentType("application/json")
            .content(json.writeValueAsBytes(correction()))).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.fix.fix_trgt_chg_id").value(1)).andExpect(jsonPath("$.data.target.fixed_yn").value("Y"))
            .andExpect(jsonPath("$.data.assignment.end_dt").value("2026-12-31"));
        verify(mapper,never()).period(any()); verify(mapper,never()).rate(any()); verify(mapper,never()).month(any());
        clearInvocations(mapper);
        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)); noBusinessWrites();
    }
    @ParameterizedTest @CsvSource({"aft_val,바른 내용을 적어 주세요.","rsn_cd,사유를 골라 주세요."})
    void correctionRequiredFieldsHaveExactMessages(String field,String message) throws Exception {
        append("EXTEND"); var n=correction(); n.remove(field);
        mvc.perform(post("/api/assignments/1/changes/1/fix").contentType("application/json")
            .content(json.writeValueAsBytes(n))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.message").value(message)); noBusinessWrites();
    }
    @ParameterizedTest @ValueSource(strings={"START","FIX"})
    void nonFixableKindsRejectDirectCall(String type) throws Exception {
        append(type); postBody(BASE+"/1/fix",correction()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("NOT_FIXABLE")); noBusinessWrites();
    }
    @Test void alreadyFixedTargetIsRejected() throws Exception {
        append("EXTEND"); mapper.markFixed(new ChangeKey(1,1)); clearInvocations(mapper);
        postBody(BASE+"/1/fix",correction()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_FIXABLE")); noBusinessWrites();
    }
    @Test void foreignHistoryIsNotFound() throws Exception {
        postBody(BASE+"/99/fix",correction()).andExpect(status().isNotFound()); noBusinessWrites();
    }
    @Test void replacedOriginalAllowsEligibleFix() throws Exception {
        state=state("REPLACED","MO","RUN"); append("EXTEND"); postBody(BASE+"/1/fix",correction()).andExpect(status().isOk());
    }
}
