package com.sicost.billingsops;

import static com.sicost.billingsops.BillingModels.*;
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

@WebMvcTest(controllers = BillingController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingExceptionAdvice.class,
    BillingCommandService.class, BillingReceivableService.class, BillingAmountService.class,
    BillingGapService.class, BillingRevenueQuery.class, CurrentUser.class, BillingApiTest.Time.class})
class BillingApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CurrentUser currentUser;
    @MockBean BillingMapper mapper;
    final Map<Long, Bill> bills = new LinkedHashMap<>();
    final List<Audit> audits = new ArrayList<>();
    Project project;
    List<Source> sources;
    List<DetailLine> details;
    List<Receipt> receipts;
    long sequence;
    @BeforeEach void setup() {
        bills.clear(); audits.clear(); sequence = 10;
        project = new Project(1, "청구 시험", null, null, "MO", "MONTH", "Y");
        sources = new ArrayList<>(); details = new ArrayList<>(); receipts = new ArrayList<>();
        bills.put(1L, bill(1, "DRAFT", 100000, 10000, "N", null, LocalDate.of(2026,10,1)));
        when(mapper.project(anyLong())).thenReturn(project);
        when(mapper.lockProject(anyLong())).thenReturn(1L);
        when(mapper.bill(anyLong())).thenAnswer(i -> bills.get(i.getArgument(0, Long.class)));
        when(mapper.lockBill(anyLong())).thenAnswer(i -> bills.get(i.getArgument(0, Long.class)));
        when(mapper.bills()).thenAnswer(i -> new ArrayList<>(bills.values()));
        when(mapper.details(anyLong())).thenAnswer(i -> details);
        when(mapper.receipts(anyLong())).thenAnswer(i -> receipts);
        when(mapper.amendments(anyLong())).thenAnswer(i -> bills.values().stream()
            .filter(b -> Objects.equals(b.orgnl_bill_id(), i.getArgument(0, Long.class))).toList());
        when(mapper.history(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.sent(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.cash(anyInt())).thenReturn(new ArrayList<>());
        when(mapper.sources(any())).thenAnswer(i -> sources);
        when(mapper.gapProjects(anyString())).thenAnswer(i -> List.of(project));
        when(mapper.nextNumber(anyString())).thenReturn("B-2610-11");
        when(mapper.insertAmendment(any())).thenAnswer(i -> {
            var w = i.getArgument(0, AmendmentWrite.class);
            var root = bills.get(w.rootId()); var a = w.input(); long id = ++sequence;
            bills.put(id, new Bill(id, w.number(), 1, null, root.atrb_ym(), a.bill_dt(), a.sply_amt(), a.vat_amt(),
                a.sply_amt() + a.vat_amt(), "DRAFT", a.txinv_no(), a.txinv_dt(), "Y", root.bill_id(), a.rcpt_plan_dt(), "N", a.reason()));
            return id;
        });
        when(mapper.state(any())).thenAnswer(i -> {
            var w = i.getArgument(0, StateWrite.class); Bill b = bills.get(w.id());
            if (!b.bill_stts_cd().equals(w.before())) return 0;
            bills.put(w.id(), new Bill(b.bill_id(), b.bill_no(), b.prj_id(), b.bill_stg_id(), b.atrb_ym(), b.bill_dt(),
                b.sply_amt(), b.vat_amt(), b.tot_amt(), w.after(), b.txinv_no(), b.txinv_dt(), b.amnd_yn(), b.orgnl_bill_id(),
                b.rcpt_plan_dt(), b.dun_stop_yn(), w.reason() == null ? b.rmrk_desc() : w.reason()));
            return 1;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    Bill bill(long id, String state, long supply, long vat, String amendment, Long root, LocalDate date) {
        return new Bill(id, "B-2610-" + id, 1, null, "2026-10", date, supply, vat, supply + vat,
            state, null, null, amendment, root, LocalDate.of(2026,10,1), "N", null);
    }
    Source source(String kind, BigDecimal mm, Long price, Long attendance) {
        return new Source(3, 4, 4, "시험 인력", "D-TEST", kind, LocalDate.of(2026,10,1), mm, price,
            new BigDecimal("10"), new BigDecimal("80"), BigDecimal.ZERO, new BigDecimal("20"), attendance);
    }
    ResultActions postJson(String suffix, Object input) throws Exception {
        return mvc.perform(post("/api/billings/1/" + suffix).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }
    Map<String,Object> amendment() { return new LinkedHashMap<>(Map.of("sply_amt", -20000, "vat_amt", -2000, "reason", "범위 감액", "bill_dt", "2026-10-23")); }
    void root() { bills.put(1L, bill(1, "CONFIRM", 100000, 10000, "N", null, LocalDate.of(2026,10,1))); }
    void noWrites() { verify(mapper, never()).state(any()); verify(mapper, never()).insertAmendment(any()); assertThat(audits).isEmpty(); }
    @Test void anonymousConfirmationCommitsStateAuditAndPreservesDetails() throws Exception {
        details.add(new DetailLine(3L,4L,4L,"인력","2026-10",BigDecimal.ONE,BigDecimal.ONE,90000L,null,null,null,0,90000,null));
        var before = List.copyOf(details);
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CONFIRM"))
            .andExpect(jsonPath("$.data.warnings.length()").value(2));
        assertThat(details).isEqualTo(before); assertThat(audits).hasSize(1);
        assertThat(audits.get(0).before()).contains("DRAFT"); assertThat(audits.get(0).after()).contains("CONFIRM");
        assertThat(audits.get(0).actor()).isEqualTo(currentUser.userId());
        var order = inOrder(mapper); order.verify(mapper).lockProject(1); order.verify(mapper).lockBill(1);
    }
    @Test void repeatedConfirmationIsStale() throws Exception {
        root(); mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL")); noWrites();
    }
    @Test void missingDateIsValidation() throws Exception {
        bills.put(1L,bill(1,"DRAFT",100,10,"N",null,null));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrites();
    }
    @Test void zeroSupplyIsValidation() throws Exception {
        bills.put(1L,bill(1,"DRAFT",0,0,"N",null,LocalDate.of(2026,10,1)));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest()); noWrites();
    }
    @Test void normalNegativeVatIsValidation() throws Exception {
        bills.put(1L,bill(1,"DRAFT",100,-1,"N",null,LocalDate.of(2026,10,1)));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest()); noWrites();
    }
    @Test void closedMonthBlocksConfirm() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); noWrites();
    }
    @Test void lostConditionalWriteIsStale() throws Exception {
        doReturn(0).when(mapper).state(any());
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL")); assertThat(audits).isEmpty();
    }
    @Test void anonymousAmendmentCreatesSeparateDraftAndNumberWithoutChangingRoot() throws Exception {
        root(); Bill original = bills.get(1L);
        mvc.perform(post("/api/billings/1/amendments").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(amendment())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.original_bill_id").value(1))
            .andExpect(jsonPath("$.data.billing.bill_no").value("B-2610-11"))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("DRAFT"));
        assertThat(bills.get(1L)).isEqualTo(original); verify(mapper).amendmentDetail(11);
        mvc.perform(get("/api/billings/overdue")).andExpect(jsonPath("$.data.items[0].billing.receivable_amt").value(110000));
        var order = inOrder(mapper); order.verify(mapper).numberLock("2026-10"); order.verify(mapper).lockProject(1); order.verify(mapper).lockBill(1);
        order.verify(mapper).insertAmendment(any()); order.verify(mapper).amendmentDetail(11); order.verify(mapper).audit(any());
        assertThat(audits).hasSize(1).allMatch(a -> Objects.equals(a.actor(), currentUser.userId()));
    }
    @ParameterizedTest @ValueSource(longs={0,1}) void amendmentRequiresNegativeSupply(long supply) throws Exception {
        var n=amendment(); n.put("sply_amt",supply); postJson("amendments",n).andExpect(status().isBadRequest()); noWrites();
    }
    @Test void amendmentRejectsPositiveVat() throws Exception {
        var n=amendment(); n.put("vat_amt",1); postJson("amendments",n).andExpect(status().isBadRequest()); noWrites();
    }
    @Test void amendmentRequiresReason() throws Exception {
        var n=amendment(); n.put("reason"," "); postJson("amendments",n).andExpect(status().isBadRequest()); noWrites();
    }
    @Test void amendmentRejectsDraftParent() throws Exception { postJson("amendments",amendment()).andExpect(status().isConflict()); noWrites(); }
    @Test void amendmentRejectsExcessSupply() throws Exception {
        root(); var n=amendment(); n.put("sply_amt",-100001); postJson("amendments",n).andExpect(status().isBadRequest()); noWrites();
    }
    @Test void amendmentRefundRequiredBelowReceived() throws Exception {
        root(); receipts.add(new Receipt(1,LocalDate.of(2026,10,2),100000,null));
        postJson("amendments",amendment()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_REQUIRED")); noWrites();
    }
    @Test void amendmentConfirmationRechecksCumulativeLimit() throws Exception {
        root(); bills.put(2L,bill(2,"CONFIRM",-90000,-9000,"Y",1L,LocalDate.of(2026,10,2)));
        bills.put(3L,bill(3,"DRAFT",-20000,-2000,"Y",1L,LocalDate.of(2026,10,3)));
        mvc.perform(post("/api/billings/3/confirm")).andExpect(status().isBadRequest()); noWrites();
    }
    @Test void amendmentConfirmationRechecksReceiptTotal() throws Exception {
        root(); bills.put(2L,bill(2,"DRAFT",-20000,-2000,"Y",1L,LocalDate.of(2026,10,2)));
        receipts.add(new Receipt(1,LocalDate.of(2026,10,2),100000,null));
        mvc.perform(post("/api/billings/2/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_REQUIRED")); noWrites();
    }
    @Test void anonymousCancelRetainsRowAndReasonAndRemovesRevenue() throws Exception {
        root();
        mvc.perform(post("/api/billings/1/cancel").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reason", "계약 취소"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CANCEL"));
        assertThat(bills.get(1L).sply_amt()).isEqualTo(100000); assertThat(bills.get(1L).rmrk_desc()).isEqualTo("계약 취소");
        assertThat(audits).hasSize(1).allMatch(a -> Objects.equals(a.actor(), currentUser.userId()));
        mvc.perform(get("/api/billings/summary")).andExpect(jsonPath("$.data.annual_confirmed_supply_amt").value(0));
        mvc.perform(get("/api/billings/overdue")).andExpect(jsonPath("$.data.items").isEmpty());
    }
    @Test void cancelMissingReasonPreservesOriginal() throws Exception {
        mvc.perform(post("/api/billings/1/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrites();
    }
    @Test void anonymousCancelDraftPreservesAmountsAndRecordsStateTransition() throws Exception {
        Bill original = bills.get(1L);
        mvc.perform(post("/api/billings/1/cancel").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reason", "초안 취소"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CANCEL"));
        assertThat(bills.get(1L).sply_amt()).isEqualTo(original.sply_amt());
        assertThat(bills.get(1L).vat_amt()).isEqualTo(original.vat_amt());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).before()).contains("DRAFT");
        assertThat(audits.get(0).after()).contains("CANCEL");
        assertThat(audits.get(0).actor()).isEqualTo(currentUser.userId());
        verify(mapper, never()).insertAmendment(any());
    }
    @Test void cancelWithReceiptIsStale() throws Exception {
        root(); receipts.add(new Receipt(1,LocalDate.of(2026,10,2),1,null)); postJson("cancel",Map.of("reason","취소")).andExpect(status().isConflict()); noWrites();
    }
    @Test void cancelClosedIsConflict() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED"); postJson("cancel",Map.of("reason","취소")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); noWrites();
    }
    @Test void cancelWithDependentAmendmentIsStale() throws Exception {
        root(); bills.put(2L,bill(2,"DRAFT",-100,-10,"Y",1L,LocalDate.of(2026,10,2)));
        postJson("cancel",Map.of("reason","취소")).andExpect(status().isConflict()); noWrites();
    }
    @Test void confirmedNegativeAmendmentCannotBeCancelled() throws Exception {
        root(); bills.put(2L,bill(2,"CONFIRM",-100,-10,"Y",1L,LocalDate.of(2026,10,2)));
        mvc.perform(post("/api/billings/2/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"취소\"}")).andExpect(status().isConflict()); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"1.5","9223372036854775808","\"-20\""})
    void monetaryPrecisionRejected(String value) throws Exception {
        mvc.perform(post("/api/billings/1/amendments").contentType(MediaType.APPLICATION_JSON)
            .content("{\"sply_amt\":"+value+",\"vat_amt\":0,\"reason\":\"감액\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); noWrites();
    }
    @Test void malformedAndPathConversionUseBillingError() throws Exception {
        mvc.perform(post("/api/billings/1/cancel").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/billings/no/confirm")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void missingBillIsNotFound() throws Exception { mvc.perform(post("/api/billings/999/confirm")).andExpect(status().isNotFound()); noWrites(); }
    @Test void previewMonthUsesRateAndFloorsWithoutWriting() throws Exception {
        sources.add(source("NEW",new BigDecimal("0.33"),100001L,null));
        mvc.perform(get("/api/billings/preview").param("projectId","1").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sply_amt").value(33000)).andExpect(jsonPath("$.data.suggested_vat_amt").value(3300)); noWrites();
    }
    @Test void previewReadsEffectiveVat() throws Exception {
        sources.add(source("NEW",BigDecimal.ONE,100000L,null)); when(mapper.vatRate("2026-10")).thenReturn(new BigDecimal("5"));
        mvc.perform(get("/api/billings/preview").param("projectId","1").param("month","2026-10"))
            .andExpect(jsonPath("$.data.suggested_vat_amt").value(5000));
    }
    @Test void previewFreeNeedsNoRate() throws Exception {
        sources.add(source("FREE",BigDecimal.ONE,null,null));
        mvc.perform(get("/api/billings/preview").param("projectId","1").param("month","2026-10"))
            .andExpect(jsonPath("$.data.missing_price_assignments").isEmpty()).andExpect(jsonPath("$.data.details[0].bill_amt").value(0));
    }
    @Test void previewMissingPriceBlocksGeneration() throws Exception {
        sources.add(source("NEW",BigDecimal.ONE,null,null));
        mvc.perform(get("/api/billings/preview").param("projectId","1").param("month","2026-10"))
            .andExpect(jsonPath("$.data.missing_price_assignments[0]").value(3));
    }
    @Test void previewInternalRejected() throws Exception {
        project=new Project(1,"내부",null,null,"IN","MONTH","Y"); when(mapper.project(1)).thenReturn(project);
        mvc.perform(get("/api/billings/preview").param("projectId","1")).andExpect(status().isBadRequest());
    }
    @Test void moDayRejected() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"월",null,null,"MO","DAY","Y"));
        mvc.perform(get("/api/billings/preview").param("projectId","1").param("month","2026-10")).andExpect(status().isBadRequest());
    }
    @Test void hourWithoutOptionalDaysHasNullMm() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"파견",null,null,"DP","HOUR","Y"));
        sources.add(new Source(3,4,4,"인력","D-TEST","NEW",LocalDate.of(2026,10,1),BigDecimal.ONE,1000L,null,new BigDecimal("80"),null,new BigDecimal("20"),1L));
        mvc.perform(get("/api/billings/preview").param("projectId","1").param("month","2026-10"))
            .andExpect(jsonPath("$.data.sply_amt").value(80000)).andExpect(jsonPath("$.data.details[0].mm_qty").isEmpty());
    }
    @Test void gapsPaidZeroExcludedAndFreeZeroMatches() throws Exception {
        sources.add(source("NEW",BigDecimal.ZERO,100000L,null));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10")).andExpect(jsonPath("$.data.total").value(0));
        sources.clear(); sources.add(source("FREE",BigDecimal.ONE,null,null));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10")).andExpect(jsonPath("$.data.items[0].judgment").value("맞음(무상)")); noWrites();
    }
    @Test void gapsPositiveNoConfirmedIsMissing() throws Exception {
        sources.add(source("NEW",BigDecimal.ONE,100000L,null));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10")).andExpect(jsonPath("$.data.items[0].judgment").value("청구 없음"));
    }
    @Test void gapsToleranceEqualityMatchesAndAmendmentsAreNegative() throws Exception {
        root(); sources.add(source("NEW",BigDecimal.ONE,100000L,null));
        bills.put(2L,bill(2,"CONFIRM",-10000,-1000,"Y",1L,LocalDate.of(2026,10,2)));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10")).andExpect(jsonPath("$.data.items[0].judgment").value("맞음"))
            .andExpect(jsonPath("$.data.items[0].difference_amt").value(10000));
    }
    @Test void gapsAttendanceWarningPrecedesNumericalMatch() throws Exception {
        project=new Project(1,"파견",null,null,"DP","DAY","Y"); when(mapper.project(1)).thenReturn(project);
        sources.add(source("NEW",BigDecimal.ONE,100000L,null));
        mvc.perform(get("/api/billings/gaps").param("month","2026-10")).andExpect(jsonPath("$.data.items[0].judgment").value("근태 미입력"));
    }
    @Test void defaultGapMonthUsesLastClosed() throws Exception {
        when(mapper.lastClosed()).thenReturn("2026-09");
        mvc.perform(get("/api/billings/gaps")).andExpect(jsonPath("$.data.month").value("2026-09"));
    }
    @Test void overdueIncludesAmendmentsAndStrictThresholdFilters() throws Exception {
        root(); bills.put(2L,bill(2,"CONFIRM",-10000,-1000,"Y",1L,LocalDate.of(2026,10,2)));
        mvc.perform(get("/api/billings/overdue")).andExpect(jsonPath("$.data.items[0].billing.receivable_amt").value(99000))
            .andExpect(jsonPath("$.data.items[0].stage").value(1)).andExpect(jsonPath("$.data.items[0].next_notification_date").value("2026-10-31"))
            .andExpect(jsonPath("$.data.items[0].sent_history").isEmpty());
        mvc.perform(get("/api/billings/overdue").param("overDays","30")).andExpect(jsonPath("$.data.total").value(0)); noWrites();
    }
    @Test void overdueNoDueDateStaysVisibleWithNullStage() throws Exception {
        root(); var b=bills.get(1L); bills.put(1L,new Bill(1,b.bill_no(),1,null,b.atrb_ym(),b.bill_dt(),100000,10000,110000,"CONFIRM",null,null,"N",null,null,"N",null));
        mvc.perform(get("/api/billings/overdue")).andExpect(jsonPath("$.data.items[0].stage").isEmpty())
            .andExpect(jsonPath("$.data.items[0].due_status").value("예정일 미입력"));
    }
    @Test void paidRootDisappears() throws Exception {
        root(); receipts.add(new Receipt(1,LocalDate.of(2026,10,2),110000,null));
        mvc.perform(get("/api/billings/overdue")).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void summarySeparatesDraftSupplyRevenueAndCash() throws Exception {
        root(); bills.put(2L,bill(2,"DRAFT",70000,7000,"N",null,LocalDate.of(2026,10,2)));
        when(mapper.cash(2026)).thenReturn(List.of(new CashDate(LocalDate.of(2026,10,3),110000)));
        mvc.perform(get("/api/billings/summary").param("year","2026"))
            .andExpect(jsonPath("$.data.annual_confirmed_supply_amt").value(100000))
            .andExpect(jsonPath("$.data.draft_supply_amt").value(70000))
            .andExpect(jsonPath("$.data.cash_receipts_vat_included_amt").value(110000)); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"page=-1","size=10","sort=unknown,asc","sort=project_name;drop,desc","stage=4","overDays=7"})
    void invalidListInputs(String query) throws Exception {
        mvc.perform(get("/api/billings/overdue?"+query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
