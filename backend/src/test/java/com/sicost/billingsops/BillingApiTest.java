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

@WebMvcTest(controllers = {BillingController.class, BillingExportController.class})
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingService.class, BillingAmountService.class,
    BillingGapService.class, BillingReceivableService.class, BillingRevenueQuery.class,
    CurrentUser.class, PermissionService.class, BillingExportService.class,
    com.sicost.common.excel.ExcelExportService.class, BillingApiTest.Time.class})
class BillingApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BillingMapper mapper;
    @MockBean com.sicost.billings.BillingMapper exportMapper;
    final Map<Long,Bill> rows = new LinkedHashMap<>();
    final List<Audit> audits = new ArrayList<>();
    Balance balance;
    @BeforeEach void setup() {
        rows.clear(); audits.clear();
        rows.put(1L, bill(1,"DRAFT",100000,10000,LocalDate.of(2026,10,1),null));
        balance = new Balance(0,0,0,0,0);
        when(mapper.bill(anyLong())).thenAnswer(i -> rows.get(i.getArgument(0,Long.class)));
        when(mapper.lockBill(anyLong())).thenAnswer(i -> rows.get(i.getArgument(0,Long.class)));
        when(mapper.bills()).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(mapper.balance(anyLong())).thenAnswer(i -> balance);
        when(mapper.closedMonths()).thenReturn(List.of());
        when(mapper.details(anyLong())).thenReturn(List.of());
        when(mapper.receipts(anyLong())).thenReturn(List.of());
        when(mapper.history(anyLong())).thenReturn(List.of());
        when(mapper.nextNumber(anyString())).thenReturn(2L);
        when(mapper.status(any())).thenAnswer(i -> {
            StatusWrite w = i.getArgument(0);
            Bill before = rows.get(w.id());
            if (!before.bill_stts_cd().equals(w.before())) return 0;
            rows.put(w.id(), bill(w.id(),w.after(),before.sply_amt(),before.vat_amt(),before.bill_dt(),before.orgnl_bill_id()));
            return 1;
        });
        when(mapper.amendment(any())).thenAnswer(i -> {
            AmendmentWrite w = i.getArgument(0);
            rows.put(2L, new Bill(2,w.number(),1,null,1,"2026-10",w.input().bill_dt(),
                w.input().sply_amt().longValueExact(),w.input().vat_amt().longValueExact(),
                w.input().sply_amt().add(w.input().vat_amt()).longValueExact(),"DRAFT",null,null,"Y",1L,
                null,w.input().reason(),"MO","시험 프로젝트",null,null));
            return 2L;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    static Bill bill(long id,String status,long supply,long vat,LocalDate date,Long root) {
        return new Bill(id,"B-2610-01",1,null,1,"2026-10",date,supply,vat,supply+vat,status,
            null,null,root==null?"N":"Y",root,LocalDate.of(2026,10,16),null,"MO","시험 프로젝트",null,null);
    }
    ResultActions postBody(String operation,Object input) throws Exception {
        return mvc.perform(post("/api/billings/1/"+operation).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input)));
    }
    void originalConfirmed() { rows.put(1L,bill(1,"CONFIRM",100000,10000,LocalDate.of(2026,10,1),null)); }
    Map<String,Object> amendment(long supply,long vat) {
        return Map.of("sply_amt",supply,"vat_amt",vat,"reason","계약 감액","bill_dt","2026-10-23");
    }
    @Test void anonymousConfirmationKeepsSnapshotAndAuditsOldAndNew() throws Exception {
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isOk())
            .andExpect(header().exists("X-Request-Id")).andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CONFIRM"))
            .andExpect(jsonPath("$.data.warnings").isArray());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).before()).contains("DRAFT");
        assertThat(audits.get(0).after()).contains("CONFIRM");
        assertThat(audits.get(0).actor()).isNull();
        verify(mapper,never()).amendmentDetail(any());
    }
    @Test void reconfirmIsConflictAndDoesNotWrite() throws Exception {
        originalConfirmed();
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        verify(mapper,never()).status(any());
    }
    @Test void missingBillIsNotFound() throws Exception {
        mvc.perform(post("/api/billings/999/confirm")).andExpect(status().isNotFound());
    }
    @Test void missingBillDateIsValidationError() throws Exception {
        rows.put(1L,bill(1,"DRAFT",100000,10000,null,null));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("bill_dt"));
        verify(mapper,never()).status(any());
    }
    @ParameterizedTest @ValueSource(longs={0,-1})
    void ordinarySupplyMustBePositive(long supply) throws Exception {
        rows.put(1L,bill(1,"DRAFT",supply,0,LocalDate.of(2026,10,1),null));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sply_amt"));
    }
    @Test void ordinaryVatCannotBeNegative() throws Exception {
        rows.put(1L,bill(1,"DRAFT",100000,-1,LocalDate.of(2026,10,1),null));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("vat_amt"));
    }
    @Test void manualSupplyMismatchAndMissingInvoiceAreWarningsNotErrors() throws Exception {
        when(mapper.details(1)).thenReturn(List.of(new DetailLine(null,null,null,null,"2026-10",null,null,null,null,null,null,0,1,null)));
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(2));
    }
    @ParameterizedTest @ValueSource(strings={"confirm","cancel","amendments"})
    void closedMonthRejectsEachWrite(String operation) throws Exception {
        when(mapper.closedMonths()).thenReturn(List.of("2026-10"));
        if (operation.equals("amendments")) originalConfirmed();
        if (operation.equals("confirm")) mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        else postBody(operation,operation.equals("cancel")?Map.of("reason","취소"):amendment(-100,-10))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).status(any()); verify(mapper,never()).amendment(any());
    }
    @Test void lostConditionalUpdateIsStale() throws Exception {
        doReturn(0).when(mapper).status(any());
        mvc.perform(post("/api/billings/1/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        assertThat(audits).isEmpty();
    }
    @Test void anonymousCancellationRetainsRowAndReasonInAudit() throws Exception {
        postBody("cancel",Map.of("reason","중복 청구" )).andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.bill_stts_cd").value("CANCEL"));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(1L).sply_amt()).isEqualTo(100000);
        assertThat(audits.get(0).reason()).isEqualTo("중복 청구");
    }
    @Test void cancellationRequiresReasonAndPreservesOriginal() throws Exception {
        Bill before = rows.get(1L);
        postBody("cancel",Map.of("reason"," ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("reason"));
        assertThat(rows.get(1L)).isEqualTo(before); assertThat(audits).isEmpty();
    }
    @Test void anyReceiptBlocksCancellation() throws Exception {
        balance = new Balance(1,0,0,1,0);
        postBody("cancel",Map.of("reason","취소")).andExpect(status().isConflict());
        verify(mapper,never()).status(any());
    }
    @Test void confirmedChildrenBlockOriginalCancellation() throws Exception {
        balance = new Balance(0,-100,-110,0,1);
        postBody("cancel",Map.of("reason","취소")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
    }
    @Test void cancelledRowCannotCancelAgain() throws Exception {
        rows.put(1L,bill(1,"CANCEL",100000,10000,LocalDate.of(2026,10,1),null));
        postBody("cancel",Map.of("reason","취소")).andExpect(status().isConflict());
    }
    @Test void anonymousAmendmentCreatesNewLinkedDraftAndLeavesOriginalUnchanged() throws Exception {
        originalConfirmed(); Bill before=rows.get(1L);
        postBody("amendments",amendment(-10000,-1000)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.bill_no").value("B-2610-02"))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("DRAFT"))
            .andExpect(jsonPath("$.data.original_bill_id").value(1)).andExpect(jsonPath("$.data.billing.bill_stg_id").isEmpty());
        assertThat(rows.get(1L)).isEqualTo(before);
        verify(mapper).amendmentDetail(argThat(d -> d.id()==2 && d.amount()==-10000));
        assertThat(audits.get(0).actor()).isNull();
    }
    @ParameterizedTest @ValueSource(longs={0,1})
    void amendmentSupplyMustBeNegative(long supply) throws Exception {
        postBody("amendments",amendment(supply,0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sply_amt"));
    }
    @Test void amendmentVatCannotBePositive() throws Exception {
        postBody("amendments",amendment(-100,1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("vat_amt"));
    }
    @Test void fractionalAmendmentAmountIsRejected() throws Exception {
        var body=new HashMap<String,Object>(amendment(-100,0)); body.put("sply_amt",-1.5);
        postBody("amendments",body).andExpect(status().isBadRequest());
    }
    @Test void amendmentRequiresReason() throws Exception {
        var body=new HashMap<String,Object>(amendment(-100,0)); body.remove("reason");
        postBody("amendments",body).andExpect(status().isBadRequest());
    }
    @Test void amendmentRequiresConfirmedOriginal() throws Exception {
        postBody("amendments",amendment(-100,0)).andExpect(status().isConflict());
    }
    @Test void cumulativeReductionCannotExceedSupply() throws Exception {
        originalConfirmed(); balance = new Balance(0,-90000,-99000,0,1);
        postBody("amendments",amendment(-10001,0)).andExpect(status().isBadRequest());
        verify(mapper,never()).amendment(any());
    }
    @Test void adjustmentBelowReceiptsRequiresRefund() throws Exception {
        originalConfirmed(); balance = new Balance(105000,0,0,1,0);
        postBody("amendments",amendment(-10000,-1000)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_REQUIRED"));
    }
    @Test void amendmentConfirmationRevalidatesRefundUnderRootLock() throws Exception {
        originalConfirmed(); rows.put(2L,bill(2,"DRAFT",-10000,-1000,LocalDate.of(2026,10,23),1L));
        balance = new Balance(105000,0,0,1,0);
        mvc.perform(post("/api/billings/2/confirm")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_REQUIRED"));
        var order=inOrder(mapper); order.verify(mapper).lockProject(1); order.verify(mapper).lockBill(1); order.verify(mapper).lockBill(2);
    }
    @ParameterizedTest @ValueSource(strings={"export","gaps/export","overdue/export"})
    void exportsAreRealWorkbooksAndRecordDownload(String path) throws Exception {
        var result=mvc.perform(get("/api/billings/"+path).param("year","2026").param("page","8").param("size","20"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn();
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(3) != null).isTrue();
        }
        verify(exportMapper).downloadAccess(anyString());
    }
    @Test void invalidJsonIsStableValidationError() throws Exception {
        mvc.perform(post("/api/billings/1/cancel").contentType(MediaType.APPLICATION_JSON).content("{oops"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void previewInternalProjectRejectedWithoutWrites() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"내부","IN",null,"Y"));
        mvc.perform(get("/api/billings/preview").param("projectId","1")).andExpect(status().isBadRequest());
        verify(mapper,never()).status(any());
    }
    @Test void previewHourDoesNotRequireDaysAndReportsMissingAttendanceAndPrice() throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"파견","DP","HOUR","Y"));
        when(mapper.sources(any())).thenReturn(List.of(new Source(3,4,null,"인력","K-4","NEW",LocalDate.of(2026,10,1),
            BigDecimal.ONE,100L,null,new BigDecimal("2.5"),null,BigDecimal.valueOf(20),"HOUR")));
        mvc.perform(get("/api/billings/preview").param("projectId","1").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sply_amt").value(250))
            .andExpect(jsonPath("$.data.details[0].mm_qty").isEmpty()).andExpect(jsonPath("$.data.suggested_vat_amt").value(25));
        verify(mapper,never()).status(any());
    }
    @Test void summaryUsesRevenueAndCashSeparately() throws Exception {
        originalConfirmed();
        when(mapper.revenue(2026)).thenReturn(List.of(new Revenue("2026-10",90000)));
        when(mapper.cash(2026)).thenReturn(List.of(new Cash(LocalDate.of(2026,11,1),1000)));
        mvc.perform(get("/api/billings/summary").param("year","2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.annual_confirmed_supply_amt").value(90000))
            .andExpect(jsonPath("$.data.cash_receipts_vat_included_amt").value(1000));
    }
    @Test void gapDefaultsToLastClosedAndDoesNotWrite() throws Exception {
        when(mapper.closedMonths()).thenReturn(List.of("2026-08","2026-09"));
        mvc.perform(get("/api/billings/gaps")).andExpect(status().isOk()).andExpect(jsonPath("$.data.month").value("2026-09"))
            .andExpect(jsonPath("$.data.items").isArray());
        verify(mapper,never()).status(any());
    }
    @Test void invalidPagingIsValidationError() throws Exception {
        mvc.perform(get("/api/billings/gaps").param("size","13")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void overdueIncludesMissingDueWithoutStageAndStrictDayFilter() throws Exception {
        originalConfirmed();
        Bill b=rows.get(1L);
        rows.put(1L,new Bill(b.bill_id(),b.bill_no(),b.prj_id(),null,1,b.atrb_ym(),b.bill_dt(),b.sply_amt(),b.vat_amt(),b.tot_amt(),
            "CONFIRM",null,null,"N",null,null,null,"MO",b.project_name(),null,null));
        mvc.perform(get("/api/billings/overdue")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].stage").isEmpty()).andExpect(jsonPath("$.data.items[0].due_status").value("예정일 미입력"))
            .andExpect(jsonPath("$.data.items[0].sent_history").isEmpty());
        mvc.perform(get("/api/billings/overdue").param("overDays","30")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
}
