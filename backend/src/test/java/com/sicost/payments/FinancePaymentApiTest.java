package com.sicost.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.excel.ExcelExportService;
import java.time.Clock;
import java.time.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.sicost.payments.FinancePaymentModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=FinancePaymentController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,FinancePaymentService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class})
class FinancePaymentApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean FinancePaymentMapper mapper;
    @MockBean Clock clock;
    Payment current;
    List<PaymentExecution> executions;
    @BeforeEach void setup() {
        current=payment("PLAN",0);
        executions=new ArrayList<>();
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        when(mapper.activeCategory("ETC")).thenReturn(true);
        when(mapper.activeBank("004")).thenReturn(true);
        when(mapper.root(7)).thenReturn(7L);when(mapper.lock(7)).thenReturn(7L);
        when(mapper.row(7)).thenAnswer(i->current);
        when(mapper.list("2026-10")).thenAnswer(i->List.of(current));
        when(mapper.chain(7)).thenAnswer(i->List.of(current));
        when(mapper.insert(any())).thenReturn(7L);
        when(mapper.executions(7)).thenAnswer(i->executions);
        doAnswer(i->{executions.add(i.getArgument(0));return null;}).when(mapper).execute(any());
        when(mapper.state(any())).thenAnswer(i->{var s=i.getArgument(0,PaymentState.class);
            current=payment(s.status(),executions.stream().mapToLong(PaymentExecution::amount).sum());return 1;});
        when(mapper.nextOpen("2026-10")).thenReturn("2026-11");
        when(mapper.candidates("2026-10")).thenReturn(List.of());
    }
    Payment payment(String state,long paid) {
        return new Payment(7,"2026-10",1,"ETC","ETC",null,null,"수령인",1000000,0,0,1000000,
            LocalDate.parse("2026-10-10"),state.equals("PAID")?LocalDate.parse("2026-10-30"):null,state,"MANUAL",null,null,
            "004","0012345678","예금주",null,LocalDateTime.parse("2026-10-01T12:00:00"),paid);
    }
    Map<String,Object> input() {
        var b=new LinkedHashMap<String,Object>();
        b.put("pay_ym","2026-10");b.put("pay_se_cd","ETC");b.put("rcvr_se_cd","ETC");b.put("recipient","수령인");
        b.put("sply_amt",1000000);b.put("vat_amt",0);b.put("ddct_amt",0);b.put("pay_plan_dt","2026-10-10");
        b.put("bank","004");b.put("account","0012345678");b.put("dpstr_nm","예금주");return b;
    }
    org.springframework.test.web.servlet.ResultActions postBody(String path,Object body) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(json.writeValueAsString(body)));
    }
    @Test void invalidMonthIsFinanceValidation() throws Exception {
        mvc.perform(get("/api/finance/payments").param("month","2026-13"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(mapper);
    }
    @Test void listIsAnonymousAndMonthlyTotalHasNoServerPaging() throws Exception {
        mvc.perform(get("/api/finance/payments").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].pay_id").value(7))
            .andExpect(jsonPath("$.data.summary[0].remaining_amount").value(1000000));
        verify(mapper,never()).state(any());
    }
    @Test void createUsesGeneratedIdentityAndServerAmount() throws Exception {
        var b=input();b.put("pay_amt",1);
        mvc.perform(post("/api/finance/payments").contentType("application/json").content(json.writeValueAsString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.pay_amt").value(1000000));
        verify(mapper).insert(argThat(w->w.input().amount()==1000000 && w.source().equals("MANUAL")));
        verify(mapper,atLeastOnce()).audit(any());
    }
    @ParameterizedTest @ValueSource(strings={"recipient","bank","account","dpstr_nm","pay_se_cd","rcvr_se_cd","pay_plan_dt"})
    void requiredFieldsHaveDistinctValidation(String field) throws Exception {
        var b=input();b.remove(field);
        postBody("/api/finance/payments",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));
        verify(mapper,never()).insert(any());
    }
    @Test void negativeComputedAmountRejected() throws Exception {
        var b=input();b.put("ddct_amt",1000001);
        postBody("/api/finance/payments",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("pay_amt"));
    }
    @Test void overflowRejected() throws Exception {
        var b=input();b.put("sply_amt",Long.MAX_VALUE);b.put("vat_amt",1);
        postBody("/api/finance/payments",b).andExpect(status().isBadRequest());
    }
    @Test void createClosedIsConflictAndUnchanged() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        postBody("/api/finance/payments",input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).insert(any());verify(mapper,never()).audit(any());
    }
    @Test void updateUsesPathAndAudits() throws Exception {
        mvc.perform(put("/api/finance/payments/7").contentType("application/json").content(json.writeValueAsString(input())))
            .andExpect(status().isOk());verify(mapper).update(argThat(w->w.id()==7));
    }
    @Test void updatePaidIsImmutable() throws Exception {
        current=payment("PAID",1000000);
        mvc.perform(put("/api/finance/payments/7").contentType("application/json").content(json.writeValueAsString(input())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));
        verify(mapper,never()).update(any());assertThat(current.pay_amt()).isEqualTo(1000000);
    }
    @Test void updateStaleVersionRejected() throws Exception {
        var b=input();b.put("updated_at","2026-09-01T00:00:00");
        mvc.perform(put("/api/finance/payments/7").contentType("application/json").content(json.writeValueAsString(b)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_PAYMENT"));
        verify(mapper,never()).update(any());
    }
    @Test void completeFullThenDuplicateIsAlreadyPaid() throws Exception {
        mvc.perform(post("/api/finance/payments/7/complete").contentType("application/json").content("{\"pay_dt\":\"2026-10-30\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.pay_stts_cd").value("PAID"));
        postBody("/api/finance/payments/7/complete",Map.of("pay_dt","2026-10-30"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));
        verify(mapper,times(1)).execute(any());
    }
    @Test void partialExecutionPreservesMillionPlanAndSixHundredThousandBalance() throws Exception {
        postBody("/api/finance/payments/7/complete",Map.of("pay_dt","2026-10-30","paid_amount",400000,"updated_at",current.updated_at().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.pay_stts_cd").value("PLAN"))
            .andExpect(jsonPath("$.data.pay_amt").value(1000000)).andExpect(jsonPath("$.data.executed_amt").value(400000));
        assertThat(current.remaining()).isEqualTo(600000);
    }
    @Test void partialRequiresVersion() throws Exception {
        postBody("/api/finance/payments/7/complete",Map.of("pay_dt","2026-10-30","paid_amount",400000))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("updated_at"));
    }
    @Test void partialStaleDoesNotExecute() throws Exception {
        postBody("/api/finance/payments/7/complete",Map.of("pay_dt","2026-10-30","paid_amount",400000,"updated_at","2026-01-01T00:00:00"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_PAYMENT"));verify(mapper,never()).execute(any());
    }
    @Test void completeActualClosedMonthRefused() throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        postBody("/api/finance/payments/7/complete",Map.of("pay_dt","2026-09-30"))
            .andExpect(status().isConflict());verify(mapper,never()).execute(any());
    }
    @Test void completeOverBalanceRefused() throws Exception {
        postBody("/api/finance/payments/7/complete",Map.of("pay_dt","2026-10-30","paid_amount",1000001,"updated_at",current.updated_at().toString()))
            .andExpect(status().isBadRequest());verify(mapper,never()).execute(any());
    }
    @Test void carryCopiesOnlyResidualAndPreservesParentPlan() throws Exception {
        current=payment("PLAN",400000);
        mvc.perform(post("/api/finance/payments/7/carry").contentType("application/json").content("{\"month\":\"2026-11\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").exists());
        verify(mapper).insert(argThat(w->w.parentId()==7 && w.input().amount()==600000 && w.input().pay_ym().equals("2026-11")));
        verify(mapper).state(argThat(s->s.status().equals("UNPAID")));
    }
    @Test void carryRejectsSkippedOpenMonth() throws Exception {
        postBody("/api/finance/payments/7/carry",Map.of("month","2026-12")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_MONTH"));verify(mapper,never()).insert(any());
    }
    @Test void carriedParentCannotComplete() throws Exception {
        current=payment("UNPAID",400000);
        postBody("/api/finance/payments/7/complete",Map.of("pay_dt","2026-10-30")).andExpect(status().isConflict());
        verify(mapper,never()).execute(any());
    }
    @Test void reopenReversesExecutionWithReason() throws Exception {
        current=payment("PAID",1000000);executions.add(new PaymentExecution(91,7,LocalDate.parse("2026-10-30"),1000000,null,null));
        mvc.perform(post("/api/finance/payments/7/reopen").contentType("application/json").content("{\"reason\":\"지급 취소\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.pay_stts_cd").value("PLAN"));
        verify(mapper).reverse(argThat(e->e.reversalId()==91 && e.amount()==-1000000 && e.reason().equals("지급 취소")));
    }
    @Test void reopenNeedsReason() throws Exception {
        postBody("/api/finance/payments/7/reopen",Map.of("reason"," ")).andExpect(status().isBadRequest());
        verify(mapper,never()).reverse(any());
    }
    @Test void reopenClosedExecutionIsImmutable() throws Exception {
        executions.add(new PaymentExecution(91,7,LocalDate.parse("2026-09-30"),1000000,null,null));
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        postBody("/api/finance/payments/7/reopen",Map.of("reason","취소")).andExpect(status().isConflict());verify(mapper,never()).reverse(any());
    }
    @Test void generateNoSourceReturnsNoFakeRows() throws Exception {
        when(mapper.list("2026-10")).thenReturn(List.of());
        mvc.perform(post("/api/finance/payments/generate").param("month","2026-10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.warnings[0]").value("자료 없음"));
        verify(mapper,never()).generate(any());
    }
    @Test void generateLeavesPaidSourceUnchanged() throws Exception {
        current=payment("PAID",1000000);when(mapper.candidates("2026-10")).thenReturn(List.of(current));when(mapper.source(current)).thenReturn(7L);
        mvc.perform(post("/api/finance/payments/generate").param("month","2026-10")).andExpect(status().isOk());
        verify(mapper,never()).generate(any());
    }
    @Test void exportIsRealXlsxWithTextAccountAndDoesNotComplete() throws Exception {
        byte[] bytes=mvc.perform(get("/api/finance/payments/export").param("month","2026-10").param("ids","7"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).contains("은행 제출용 아님","1000000");
            assertThat(book.getSheetAt(0).getRow(4).getCell(3).getStringCellValue()).isEqualTo("0012345678");
        }
        verify(mapper,never()).execute(any());verify(mapper).access(anyString());
    }
    @Test void exportRejectsPaid() throws Exception {
        current=payment("PAID",1000000);
        mvc.perform(get("/api/finance/payments/export").param("month","2026-10").param("ids","7")).andExpect(status().isBadRequest());
    }
    @Test void missingPaymentIs404() throws Exception {
        postBody("/api/finance/payments/99/complete",Map.of("pay_dt","2026-10-30")).andExpect(status().isNotFound());
    }
    @Test void unexpectedErrorDoesNotLeakSql() throws Exception {
        when(mapper.list("2026-10")).thenThrow(new IllegalStateException("private SQL details"));
        mvc.perform(get("/api/finance/payments").param("month","2026-10")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
}
