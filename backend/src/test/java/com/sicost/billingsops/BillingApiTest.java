package com.sicost.billingsops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=BillingController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,BillingCommandService.class,
    BillingAmountService.class,BillingGapService.class,BillingReceivableService.class,
    BillingRevenueQuery.class,CurrentUser.class,PermissionService.class})
class BillingApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BillingMapper mapper;
    @MockBean Clock clock;
    final Map<Long,State> rows=new LinkedHashMap<>();
    final List<Audit> audits=new ArrayList<>();
    State state(long id,String status,long supply,long vat,LocalDate date,String amendment,Long root,String month) {
        return new State(id,"B-2610-"+id,1,amendment.equals("Y")?null:3L,month,date,supply,vat,supply+vat,
            status,null,null,LocalDate.of(2026,9,29),amendment,root,null,LocalDate.of(2026,10,1),
            "청구 프로젝트",2L,"고객사","MO","영업1","N",0,supply+vat);
    }
    @BeforeEach void setup() {
        rows.clear();audits.clear();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-29T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        rows.put(7L,state(7,"DRAFT",100000,10000,LocalDate.of(2026,10,1),"N",null,"2026-10"));
        when(mapper.state(anyLong())).thenAnswer(i->rows.get(i.getArgument(0,Long.class)));
        when(mapper.lockBill(anyLong())).thenAnswer(i->i.getArgument(0));
        when(mapper.lockProject(1)).thenReturn(1L);
        when(mapper.project(1)).thenReturn(new Project(1,"청구 프로젝트","MO","MONTH","Y"));
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.balance(7)).thenReturn(new Balance(100000,110000,0));
        when(mapper.states(anyInt())).thenAnswer(i->List.copyOf(rows.values()));
        when(mapper.transition(any())).thenAnswer(i->{
            Transition t=i.getArgument(0);State old=rows.get(t.id());
            rows.put(t.id(),state(old.bill_id(),t.after(),old.sply_amt(),old.vat_amt(),old.bill_dt(),old.amnd_yn(),old.orgnl_bill_id(),old.atrb_ym()));return 1;
        });
        when(mapper.insertAmendment(any())).thenAnswer(i->{
            AmendmentWrite w=i.getArgument(0);var a=w.input();
            rows.put(8L,state(8,"DRAFT",a.sply_amt(),a.vat_amt(),a.bill_dt(),"Y",w.original().bill_id(),w.original().atrb_ym()));return 8L;
        });
        doAnswer(i->{audits.add(i.getArgument(0));return null;}).when(mapper).audit(any());
    }
    @Test void anonymousConfirmationAllowsMismatchAndMissingInvoiceWithoutChangingSnapshots() throws Exception {
        mvc.perform(post("/api/billings/7/confirm")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CONFIRM"))
            .andExpect(jsonPath("$.data.warnings.length()").value(2));
        assertThat(audits).hasSize(1);assertThat(audits.get(0).actor()).isNull();
        assertThat(json.readTree(audits.get(0).before()).get("bill_stts_cd").asText()).isEqualTo("DRAFT");
        assertThat(json.readTree(audits.get(0).after()).get("bill_stts_cd").asText()).isEqualTo("CONFIRM");
        verify(mapper).lockProject(1);verify(mapper).lockBill(7);
    }
    @ParameterizedTest @CsvSource({"0,0,2026-10-01","1,-1,2026-10-01","1,0,NULL"})
    void invalidConfirmationAmountsOrDateNeverChange(long supply,long vat,String date)throws Exception {
        rows.put(7L,state(7,"DRAFT",supply,vat,date.equals("NULL")?null:LocalDate.parse(date),"N",null,"2026-10"));
        mvc.perform(post("/api/billings/7/confirm")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper,never()).transition(any());assertThat(audits).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"PLAN","CONFIRM","CANCEL"})
    void onlyDraftMayConfirm(String status)throws Exception {
        rows.put(7L,state(7,status,100,10,LocalDate.of(2026,10,1),"N",null,"2026-10"));
        mvc.perform(post("/api/billings/7/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        assertThat(rows.get(7L).bill_stts_cd()).isEqualTo(status);
    }
    @Test void lateConditionalTransitionIsConflict()throws Exception {
        doReturn(0).when(mapper).transition(any());
        mvc.perform(post("/api/billings/7/confirm")).andExpect(status().isConflict());assertThat(audits).isEmpty();
    }
    @Test void anonymousCancelKeepsAmountsAndAuditsReason()throws Exception {
        mvc.perform(post("/api/billings/7/cancel").contentType("application/json").content("{\"reason\":\"계약 취소\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CANCEL"))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100000));
        assertThat(audits.get(0).reason()).isEqualTo("계약 취소");assertThat(rows).containsKey(7L);
    }
    @Test void cancelReasonIsMandatory()throws Exception {
        mvc.perform(post("/api/billings/7/cancel").contentType("application/json").content("{\"reason\":\" \"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("reason"));
        verify(mapper,never()).transition(any());
    }
    @Test void receiptPreventsCancellationEvenWhenAmountIsZero()throws Exception {
        when(mapper.receiptCount(7)).thenReturn(1L);
        mvc.perform(post("/api/billings/7/cancel").contentType("application/json").content("{\"reason\":\"취소\"}"))
            .andExpect(status().isConflict());verify(mapper,never()).transition(any());
    }
    @Test void liveAmendmentPreventsOriginalCancellation()throws Exception {
        when(mapper.activeAmendments(7)).thenReturn(1L);
        mvc.perform(post("/api/billings/7/cancel").contentType("application/json").content("{\"reason\":\"취소\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
    }
    @ParameterizedTest @ValueSource(strings={"confirm","cancel","amendments"})
    void closedMonthPreventsEveryCommand(String command)throws Exception {
        rows.put(7L,state(7,command.equals("amendments")?"CONFIRM":"DRAFT",100000,10000,LocalDate.of(2026,9,1),"N",null,"2026-09"));
        mvc.perform(post("/api/billings/7/"+command).contentType("application/json").content(
            command.equals("amendments")?"{\"reason\":\"수정\",\"sply_amt\":-1,\"vat_amt\":0}":"{\"reason\":\"취소\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).transition(any());verify(mapper,never()).insertAmendment(any());
    }
    @Test void anonymousAmendmentCreatesNewNegativeDraftAndLeavesOriginalUnchanged()throws Exception {
        State original=state(7,"CONFIRM",100000,10000,LocalDate.of(2026,10,1),"N",null,"2026-10");rows.put(7L,original);
        mvc.perform(post("/api/billings/7/amendments").contentType("application/json")
            .content("{\"reason\":\"감액\",\"sply_amt\":-10000,\"vat_amt\":-1000}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.original_bill_id").value(7))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("DRAFT"));
        assertThat(rows.get(7L)).isEqualTo(original);assertThat(rows.get(8L).bill_stg_id()).isNull();
        assertThat(rows.get(8L).bill_no()).isNotEqualTo(original.bill_no());
        assertThat(audits).hasSize(1);assertThat(audits.get(0).type()).isEqualTo("INSERT");
        var order=inOrder(mapper);order.verify(mapper).lockNumber("B-2610-");order.verify(mapper).lockProject(1);order.verify(mapper).lockBill(7);
    }
    @ParameterizedTest @ValueSource(strings={"{\"reason\":\"수정\",\"sply_amt\":0,\"vat_amt\":0}","{\"reason\":\"수정\",\"sply_amt\":-1,\"vat_amt\":1}","{\"reason\":\"수정\",\"sply_amt\":-1.5,\"vat_amt\":0}","{\"reason\":\"수정\",\"sply_amt\":-9223372036854775809,\"vat_amt\":0}"})
    void invalidAmendmentsRejectBeforeWrite(String body)throws Exception {
        mvc.perform(post("/api/billings/7/amendments").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper,never()).insertAmendment(any());
    }
    @Test void amendmentCannotExceedCurrentSupply()throws Exception {
        rows.put(7L,state(7,"CONFIRM",100000,10000,LocalDate.of(2026,10,1),"N",null,"2026-10"));
        mvc.perform(post("/api/billings/7/amendments").contentType("application/json").content("{\"reason\":\"감액\",\"sply_amt\":-100001,\"vat_amt\":0}"))
            .andExpect(status().isBadRequest());verify(mapper,never()).insertAmendment(any());
    }
    @Test void refundIsRevalidatedAtCreation()throws Exception {
        rows.put(7L,state(7,"CONFIRM",100000,10000,LocalDate.of(2026,10,1),"N",null,"2026-10"));
        when(mapper.balance(7)).thenReturn(new Balance(100000,110000,105000));
        mvc.perform(post("/api/billings/7/amendments").contentType("application/json").content("{\"reason\":\"감액\",\"sply_amt\":-10000,\"vat_amt\":0}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_REQUIRED"));
    }
    @Test void refundIsRevalidatedAtConfirmationUnderOriginalLock()throws Exception {
        rows.put(7L,state(7,"CONFIRM",100000,10000,LocalDate.of(2026,10,1),"N",null,"2026-10"));
        rows.put(8L,state(8,"DRAFT",-10000,0,LocalDate.of(2026,10,2),"Y",7L,"2026-10"));
        when(mapper.balance(7)).thenReturn(new Balance(100000,110000,105000));
        mvc.perform(post("/api/billings/8/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_REQUIRED"));
        var order=inOrder(mapper);order.verify(mapper).lockBill(7);order.verify(mapper).lockBill(8);
    }
    @Test void anonymousGapListHasResponseContractAndNoWrites()throws Exception {
        mvc.perform(get("/api/billings/gaps")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items").isArray());
        verify(mapper,never()).transition(any());verify(mapper,never()).audit(any());
    }
    @Test void anonymousOverdueListHasResponseContractAndNoWrites()throws Exception {
        mvc.perform(get("/api/billings/overdue")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items").isArray());
        verify(mapper,never()).transition(any());verify(mapper,never()).audit(any());
    }
    @Test void anonymousSummaryHasResponseContractAndNoWrites()throws Exception {
        mvc.perform(get("/api/billings/summary")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.year").value(2026))
            .andExpect(jsonPath("$.data.monthly_revenue").isArray())
            .andExpect(jsonPath("$.data.draft_supply_amt").value(100000));
        verify(mapper,never()).transition(any());verify(mapper,never()).audit(any());
    }
    @Test void anonymousPreviewHasResponseContractAndNoWrites()throws Exception {
        mvc.perform(get("/api/billings/preview").param("projectId","1").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.details").isArray());
        verify(mapper,never()).transition(any());verify(mapper,never()).audit(any());
    }
    @Test void invalidGapMonthReturnsFieldWithoutWriting() throws Exception {
        mvc.perform(get("/api/billings/gaps").param("month","2026-13"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper,never()).transition(any());verify(mapper,never()).audit(any());
    }
    @Test void invalidSummaryYearReturnsFieldWithoutWriting() throws Exception {
        mvc.perform(get("/api/billings/summary").param("year","invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper,never()).transition(any());verify(mapper,never()).audit(any());
    }
    @Test void missingPreviewProjectIsValidationError()throws Exception {
        mvc.perform(get("/api/billings/preview")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void invalidSortNeverReachesQueries()throws Exception {
        mvc.perform(get("/api/billings/overdue").param("sort","bill_id;DROP,asc"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"));
    }
    @Test void absentBillIs404()throws Exception {
        mvc.perform(post("/api/billings/999/confirm")).andExpect(status().isNotFound());
    }
}
