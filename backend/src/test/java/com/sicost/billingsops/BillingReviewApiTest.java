package com.sicost.billingsops;

import static com.sicost.billingsops.BillingReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.security.*;
import com.sicost.shared.SharedReadMapper;
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

@WebMvcTest(controllers=BillingReviewController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,BillingLifecycleService.class,
    BillingPreviewService.class,BillingReviewService.class,BillingReceivableService.class,
    BillingAmountService.class,BillingGapService.class,CurrentUser.class,PermissionService.class,
    BillingReviewApiTest.Time.class})
class BillingReviewApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BillingReviewMapper mapper;
    @MockBean BillingMutationService mutations;
    @MockBean BillingRevenueQuery revenue;
    @MockBean SharedReadMapper shared;
    final Map<Long,Bill> rows=new LinkedHashMap<>();
    final Map<Long,Header> headers=new HashMap<>();
    Bill bill(long id,String status,long supply,long vat) {
        return new Bill(id,null,false,"B-2610-"+id,1,"청구 시험",null,null,"MO","2026-10",
            LocalDate.of(2026,10,23),supply,vat,supply+vat,status,null,null,
            LocalDate.of(2026,9,23),0,supply+vat,status.equals("CONFIRM")?supply+vat:0,null);
    }
    @BeforeEach void setup() {
        rows.clear(); headers.clear(); rows.put(1L,bill(1,"DRAFT",100000,10000));
        headers.put(1L,new Header(1,null,"N",null));
        when(mapper.bill(anyLong())).thenAnswer(i->rows.get(i.getArgument(0,Long.class)));
        when(mapper.header(anyLong())).thenAnswer(i->headers.get(i.getArgument(0,Long.class)));
        when(mutations.lock(anyLong())).thenAnswer(i->{
            var b=rows.get(i.getArgument(0,Long.class));
            if(b==null) throw ApiException.notFound("청구가 없습니다.");
            return new State(b.bill_id(),b.prj_id(),headers.get(b.bill_id()).orgnl_bill_id(),b.bill_stts_cd(),
                b.contract_type(),b.atrb_ym(),b.bill_dt(),null,LocalDateTime.of(2026,10,1,0,0));
        });
        when(mapper.transition(any())).thenAnswer(i->{var t=i.getArgument(0,Transition.class);var b=rows.get(t.id());
            rows.put(t.id(),bill(t.id(),t.after(),b.sply_amt(),b.vat_amt()));return 1;});
        when(mapper.insertAmendment(any())).thenAnswer(i->{var w=i.getArgument(0,AmendmentWrite.class);
            rows.put(2L,bill(2,"DRAFT",w.input().sply_amt(),w.input().vat_amt()));
            headers.put(2L,new Header(2,1L,"Y",w.input().reason()));return 2L;});
        when(mutations.nextNumber()).thenReturn("B-2610-02");
        when(revenue.balance(1)).thenReturn(new Balance(1,"CONFIRM","N",110000,0,0));
        when(revenue.confirmedSupply(any())).thenReturn(new Supply(0,0));
    }
    ResultActions postBody(String action,Object body) throws Exception {
        return mvc.perform(post("/api/billings/1/"+action).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
    }
    Map<String,Object> amendment() { return Map.of("sply_amt",-10000,"vat_amt",-1000,"reason","감액"); }
    @Test void anonymousConfirmFreezesDraftAndAuditsWithoutSourceWrites() throws Exception {
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CONFIRM"))
            .andExpect(jsonPath("$.data.details").isArray()).andExpect(jsonPath("$.data.warnings").isNotEmpty());
        verify(mutations).audit(argThat(a->a.before().equals("DRAFT") && a.after().equals("CONFIRM")));
        verify(mapper,never()).amendmentDetail(any());
    }
    @ParameterizedTest @ValueSource(strings={"CONFIRM","CANCEL","PLAN"})
    void repeatedOrNonDraftConfirmIsStale(String state) throws Exception {
        rows.put(1L,bill(1,state,100000,10000));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        verify(mapper,never()).transition(any());
    }
    @Test void confirmMissingDateIs400() throws Exception {
        var b=rows.get(1L);
        rows.put(1L,new Bill(b.bill_id(),null,false,b.bill_no(),1,b.project_name(),null,null,"MO","2026-10",null,
            100000,10000,110000,"DRAFT",null,null,null,0,110000,0,null));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bill_dt"));
        verify(mapper,never()).transition(any());
    }
    @Test void confirmZeroSupplyIs400() throws Exception {
        rows.put(1L,bill(1,"DRAFT",0,0));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest());
    }
    @Test void confirmNegativeOrdinaryVatIs400() throws Exception {
        rows.put(1L,bill(1,"DRAFT",100000,-1));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest());
    }
    @Test void manualSupplyMismatchWarnsAndConfirms() throws Exception {
        when(mapper.details(1)).thenReturn(List.of(new Line(9L,8L,8L,"시험","2026-10",null,null,100L,null,null,null,0,90000,null)));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.warnings[1]").value("원천 상세 합과 수동 공급가액이 다릅니다. 확정할 수 있습니다."));
    }
    @Test void conditionalUpdateFailureIsStale() throws Exception {
        doReturn(0).when(mapper).transition(any());
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isConflict());
        verify(mutations,never()).audit(any());
    }
    @ParameterizedTest @ValueSource(strings={"confirm","cancel","amendments"})
    void closedMonthsRejectEveryMutation(String operation) throws Exception {
        rows.put(1L,bill(1,operation.equals("confirm")?"DRAFT":"CONFIRM",100000,10000));
        doThrow(ApiException.conflict("CLOSED_MONTH","마감", "month")).when(mutations).requireOpen(any(State.class));
        var result=operation.equals("confirm")?mvc.perform(post("/api/billings/1/confirm")):
            postBody(operation,operation.equals("cancel")?Map.of("reason","취소"):amendment());
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).transition(any());verify(mapper,never()).insertAmendment(any());
    }
    @Test void anonymousCancelLeavesPhysicalBillAndWritesReasonAudit() throws Exception {
        postBody("cancel",Map.of("reason","계약 취소")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CANCEL"));
        assertThat(rows).containsKey(1L);
        verify(mutations).audit(argThat(a->a.reason().equals("계약 취소") && a.after().equals("CANCEL")));
    }
    @Test void cancelReasonRequiredWithoutChanges() throws Exception {
        var before=rows.get(1L);postBody("cancel",Map.of("reason"," ")).andExpect(status().isBadRequest());
        assertThat(rows.get(1L)).isEqualTo(before);verify(mapper,never()).transition(any());
    }
    @Test void cancelWithReceiptIsConflict() throws Exception {
        when(mapper.receipts(1)).thenReturn(List.of(new Receipt(1,LocalDate.of(2026,10,1),1,null)));
        postBody("cancel",Map.of("reason","취소")).andExpect(status().isConflict());verify(mapper,never()).transition(any());
    }
    @Test void originalWithConfirmedAmendmentCannotCancel() throws Exception {
        when(mapper.confirmedAmendments(1)).thenReturn(1L);
        postBody("cancel",Map.of("reason","취소")).andExpect(status().isConflict());
    }
    @Test void anonymousAmendCreatesLinkedDraftAndOriginalUnchanged() throws Exception {
        rows.put(1L,bill(1,"CONFIRM",100000,10000));var before=rows.get(1L);
        postBody("amendments",amendment()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.original_bill_id").value(1))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("DRAFT"));
        assertThat(rows.get(1L)).isEqualTo(before);
        verify(mapper).insertAmendment(argThat(w->w.number().equals("B-2610-02") && w.original().bill_id()==1));
        verify(mapper).amendmentDetail(argThat(w->w.id()==2 && w.month().equals("2026-10")));
        verify(mutations,times(3)).audit(any());
    }
    @ParameterizedTest @ValueSource(strings={"0","1","1.5","9223372036854775808","\"-1\""})
    void amendmentInvalidSupply(String supply) throws Exception {
        mvc.perform(post("/api/billings/1/amendments").contentType(MediaType.APPLICATION_JSON)
            .content("{\"sply_amt\":"+supply+",\"vat_amt\":0,\"reason\":\"변경\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper,never()).insertAmendment(any());
    }
    @Test void amendmentPositiveVatIs400() throws Exception {
        postBody("amendments",Map.of("sply_amt",-1,"vat_amt",1,"reason","변경")).andExpect(status().isBadRequest());
    }
    @Test void amendmentLimitIsRechecked() throws Exception {
        rows.put(1L,bill(1,"CONFIRM",100,10));
        postBody("amendments",amendment()).andExpect(status().isBadRequest());verify(mapper,never()).insertAmendment(any());
    }
    @Test void amendmentRefundIs409() throws Exception {
        rows.put(1L,bill(1,"CONFIRM",100000,10000));
        when(revenue.balance(1)).thenReturn(new Balance(1,"CONFIRM","N",110000,0,105000));
        postBody("amendments",amendment()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_REQUIRED"));
        verify(mapper,never()).insertAmendment(any());
    }
    @Test void missingBillIs404WithoutInventedAuthentication() throws Exception {
        mvc.perform(post("/api/billings/90/confirm")).andExpect(status().isNotFound());
    }
    @Test void summarySeparatesSupplyCashAndDraft() throws Exception {
        when(revenue.monthlyRevenue(any())).thenReturn(List.of(new Revenue("2026-10",100)));
        when(revenue.cashReceipts(any())).thenReturn(List.of(new CashReceipt(1,1,LocalDate.of(2026,10,23),110)));
        when(mapper.draftSupply(any())).thenReturn(50L);
        mvc.perform(get("/api/billings/summary")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.annual_confirmed_supply_amt").value(100))
            .andExpect(jsonPath("$.data.cash_receipts_vat_included_amt").value(110))
            .andExpect(jsonPath("$.data.draft_supply_amt").value(50));
    }
    @ParameterizedTest @ValueSource(strings={"page=-1","size=10","overDays=7","stage=4","sort=bad,asc"})
    void overdueBoundaryValidation(String query) throws Exception {
        mvc.perform(get("/api/billings/overdue?"+query)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void overdueHasNextDateCumulativeRolesAndNoSentHistory() throws Exception {
        when(mapper.originals(any())).thenReturn(List.of(bill(1,"CONFIRM",100,10)));
        mvc.perform(get("/api/billings/overdue")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].stage").value(2))
            .andExpect(jsonPath("$.data.items[0].next_notification_date").value("2026-11-22"))
            .andExpect(jsonPath("$.data.items[0].sent_history").isEmpty())
            .andExpect(jsonPath("$.data.items[0].recipient_roles[0]").value("영업 담당자"));
        mvc.perform(get("/api/billings/overdue?overDays=30")).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void gapsDefaultLastClosedAndReadOnly() throws Exception {
        when(mapper.lastClosed()).thenReturn("2026-09");
        mvc.perform(get("/api/billings/gaps")).andExpect(status().isOk()).andExpect(jsonPath("$.data.month").value("2026-09"));
        verify(mapper,never()).transition(any());verify(mutations,never()).audit(any());
    }
    @Test void previewInternalProjectIs400() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"내부","IN",null,"Y"));
        mvc.perform(get("/api/billings/preview?projectId=1")).andExpect(status().isBadRequest());
    }
    @Test void previewTurnkeyUsesStageWithoutSaving() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"턴키","TK","STAGE","Y"));
        when(mapper.stage(2)).thenReturn(new Stage(2,1,100000,LocalDate.of(2026,10,23)));
        mvc.perform(get("/api/billings/preview?projectId=1&stageId=2")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sply_amt").value(100000)).andExpect(jsonPath("$.data.suggested_vat_amt").value(10000));
        verify(mapper,never()).insertAmendment(any());
    }
    Source source(String kind,Long price,Long attendance) {
        return new Source(3,4,4,"투입 인력","K-9001",kind,LocalDate.of(2026,10,1),
            new java.math.BigDecimal("1.00"),price,attendance,new java.math.BigDecimal("10.0"),
            new java.math.BigDecimal("80.0"),java.math.BigDecimal.ZERO,new java.math.BigDecimal("20.0"));
    }
    void monthlySource(String type,String settlement,Source source) {
        var project=new Project(1,"청구 시험",type,settlement,"Y");
        when(mapper.project(1)).thenReturn(project);
        when(mapper.gapProjects(anyString())).thenReturn(List.of(project));
        when(mapper.sources(any())).thenReturn(List.of(source));
    }
    @Test void previewMonthlyFloorsWonAndUsesContractPerson() throws Exception {
        monthlySource("MO","MONTH",source("NEW",100001L,null));
        mvc.perform(get("/api/billings/preview?projectId=1&month=2026-10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sply_amt").value(100001))
            .andExpect(jsonPath("$.data.suggested_vat_amt").value(10000))
            .andExpect(jsonPath("$.data.details[0].cntrct_dev_id").value(4));
    }
    @Test void previewDayUsesActualWorkdaysNotMm() throws Exception {
        monthlySource("DP","DAY",source("NEW",1000L,8L));
        mvc.perform(get("/api/billings/preview?projectId=1&month=2026-10"))
            .andExpect(jsonPath("$.data.sply_amt").value(10000)).andExpect(jsonPath("$.data.details[0].mm_qty").value(0.5));
    }
    @Test void previewHourlyNeverInventsOvertimeMultiplier() throws Exception {
        monthlySource("DP","HOUR",source("NEW",1000L,8L));
        mvc.perform(get("/api/billings/preview?projectId=1&month=2026-10"))
            .andExpect(jsonPath("$.data.sply_amt").value(80000)).andExpect(jsonPath("$.data.details[0].ovtm_amt").value(0));
    }
    @Test void previewMissingPriceBlocksCreationThroughExplicitAssignments() throws Exception {
        monthlySource("MO","MONTH",source("NEW",null,null));
        mvc.perform(get("/api/billings/preview?projectId=1&month=2026-10"))
            .andExpect(jsonPath("$.data.missing_price_assignments[0]").value(3)).andExpect(jsonPath("$.data.details").isEmpty());
    }
    @Test void attendanceMissingPrecedesMatchedClassification() throws Exception {
        monthlySource("DP","DAY",source("NEW",1000L,null));
        mvc.perform(get("/api/billings/gaps?month=2026-10"))
            .andExpect(jsonPath("$.data.items[0].judgment").value("근태 미입력"))
            .andExpect(jsonPath("$.data.items[0].attendance_missing").value(true));
    }
    @Test void freeOnlyZeroIsMatchedFree() throws Exception {
        monthlySource("MO","MONTH",source("FREE",null,null));
        mvc.perform(get("/api/billings/gaps?month=2026-10"))
            .andExpect(jsonPath("$.data.items[0].judgment").value("맞음(무상)"));
    }
    @Test void paidZeroBasisAndZeroConfirmedIsOmitted() throws Exception {
        monthlySource("MO","MONTH",source("NEW",0L,null));
        mvc.perform(get("/api/billings/gaps?month=2026-10")).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void positiveBasisWithoutConfirmedBillIsMissingAndNewPersonIsSuspected() throws Exception {
        monthlySource("MO","MONTH",source("NEW",100000L,null));
        mvc.perform(get("/api/billings/gaps?month=2026-10"))
            .andExpect(jsonPath("$.data.items[0].judgment").value("청구 없음"))
            .andExpect(jsonPath("$.data.items[0].suspected_people[0].management_number").value("K-9001"));
    }
    @Test void toleranceBoundaryIsMatchedButStrictlyLargerDifferenceIsUnderbilled() throws Exception {
        monthlySource("MO","MONTH",source("NEW",100000L,null));
        when(revenue.confirmedSupply(any())).thenReturn(new Supply(90000,1));
        mvc.perform(get("/api/billings/gaps?month=2026-10")).andExpect(jsonPath("$.data.items[0].judgment").value("맞음"));
        when(revenue.confirmedSupply(any())).thenReturn(new Supply(89999,1));
        mvc.perform(get("/api/billings/gaps?month=2026-10")).andExpect(jsonPath("$.data.items[0].judgment").value("적게 청구"));
    }
    @Test void gapFiltersAndTotalUseSameRows() throws Exception {
        monthlySource("MO","MONTH",source("NEW",100000L,null));
        mvc.perform(get("/api/billings/gaps?month=2026-10&judgment=맞음"))
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.total_difference_amt").value(0));
    }
}
