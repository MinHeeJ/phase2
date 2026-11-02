package com.sicost.tax;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.tax.CorporateTaxModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;

@WebMvcTest(controllers=CorporateTaxController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,CorporateTaxService.class,CurrentUser.class,PermissionService.class})
class CorporateTaxApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean CorporateTaxMapper mapper;
    @MockBean com.sicost.common.finance.PaymentExecutionService executions;
    @BeforeEach void setup() {
        when(mapper.plan("2026")).thenReturn(new Annual(100L,"기존"));
        when(mapper.snapshots("2026")).thenReturn(List.of(new Snapshot("2026-01",true,10L)));
        when(mapper.history("2026")).thenReturn(List.of());
        when(mapper.payments("2026")).thenReturn(List.of());
        when(mapper.lockPayments("2026")).thenReturn(List.of());
        when(mapper.savePayment(any())).thenReturn(55L);
    }
    @Test void anonymousPlanAllocatesRemainderToLastOpenMonth() throws Exception {
        mvc.perform(get("/api/finance/tax/plan").param("year","2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.annual_amount").value(100))
            .andExpect(jsonPath("$.data.months[0].amount").value(10))
            .andExpect(jsonPath("$.data.months[1].amount").value(8))
            .andExpect(jsonPath("$.data.months[11].amount").value(10));
        verify(mapper,never()).savePlan(org.mockito.ArgumentMatchers.any());
    }
    @Test void planSaveRecordsPreviousAndNewAmountAndReason() throws Exception {
        mvc.perform(put("/api/finance/tax/plan").param("year","2026").contentType("application/json")
            .content("{\"annual_amount\":120,\"reason\":\"추정 변경\"}")).andExpect(status().isOk());
        verify(mapper).lockYear("2026");
        verify(mapper).savePlan(new PlanWrite("2026",new PlanInput(120,"추정 변경"),100L,null));
        verify(mapper).appendHistory(new PlanWrite("2026",new PlanInput(120,"추정 변경"),100L,null));
    }
    @Test void firstPlanCanBeCreatedWithoutAnySeed() throws Exception {
        when(mapper.plan("2026")).thenReturn(null,new Annual(0L,"최초"));
        when(mapper.snapshots("2026")).thenReturn(List.of());
        mvc.perform(put("/api/finance/tax/plan?year=2026").contentType("application/json")
            .content("{\"annual_amount\":0,\"reason\":\"최초\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.annual_amount").value(0));
        verify(mapper).appendHistory(new PlanWrite("2026",new PlanInput(0,"최초"),null,null));
    }
    @Test void missingPlanIs404() throws Exception {
        when(mapper.plan("2026")).thenReturn(null);
        mvc.perform(get("/api/finance/tax/plan?year=2026")).andExpect(status().isNotFound());
    }
    @Test void missingClosedSnapshotIsNullNotRecalculated() throws Exception {
        when(mapper.snapshots("2026")).thenReturn(List.of(new Snapshot("2026-01",true,null)));
        mvc.perform(get("/api/finance/tax/plan?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.months[0].amount").isEmpty())
            .andExpect(jsonPath("$.data.months[1].amount").isEmpty())
            .andExpect(jsonPath("$.data.months[0].warning").value("MISSING_CLOSED_SNAPSHOT"));
    }
    @Test void missingClosedSnapshotBlocksPlanWrite() throws Exception {
        when(mapper.snapshots("2026")).thenReturn(List.of(new Snapshot("2026-01",true,null)));
        planWrite("{\"annual_amount\":120,\"reason\":\"변경\"}",409,"CLOSED_MONTH");
    }
    @Test void allClosedRefusesChangeAndReturnsFixedSnapshots() throws Exception {
        when(mapper.snapshots("2026")).thenReturn(java.util.stream.IntStream.rangeClosed(1,12)
            .mapToObj(m->new Snapshot(YearMonth.of(2026,m).toString(),true,1L)).toList());
        planWrite("{\"annual_amount\":120,\"reason\":\"변경\"}",409,"CLOSED_MONTH");
        mvc.perform(get("/api/finance/tax/plan?year=2026")).andExpect(jsonPath("$.data.editable").value(false))
            .andExpect(jsonPath("$.data.months[11].amount").value(1));
    }
    @Test void annualBelowClosedIsValidationError() throws Exception {
        planWrite("{\"annual_amount\":9,\"reason\":\"변경\"}",400,"VALIDATION_ERROR");
    }
    @Test void annualEqualClosedAllocatesZeroToOpen() throws Exception {
        when(mapper.plan("2026")).thenReturn(new Annual(10L,"기존"));
        mvc.perform(get("/api/finance/tax/plan?year=2026")).andExpect(jsonPath("$.data.months[11].amount").value(0));
    }
    @ParameterizedTest @ValueSource(strings={"{\"annual_amount\":-1,\"reason\":\"변경\"}","{\"annual_amount\":1.5,\"reason\":\"변경\"}","{\"annual_amount\":9223372036854775808,\"reason\":\"변경\"}","{\"annual_amount\":1}","{\"annual_amount\":1,\"reason\":\" \"}","{\"year\":2025,\"annual_amount\":1,\"reason\":\"변경\"}"})
    void invalidPlanNeverMutates(String body) throws Exception {planWrite(body,400,"VALIDATION_ERROR");}
    void planWrite(String body,int status,String code) throws Exception {
        mvc.perform(put("/api/finance/tax/plan?year=2026").contentType("application/json").content(body))
            .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
        verify(mapper,never()).savePlan(any());verify(mapper,never()).appendHistory(any());
    }
    @ParameterizedTest @CsvSource({"plan,GET","payments,GET","plan,PUT","payments,PUT"})
    void everyOperationRequiresValidYear(String resource,String method) throws Exception {
        var request=method.equals("GET")?get("/api/finance/tax/"+resource):put("/api/finance/tax/"+resource).contentType("application/json").content("{}");
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("year"));
    }
    @Test void paymentsReadIsAnonymousAndEmptyWithSeparateSuggestions() throws Exception {
        mvc.perform(get("/api/finance/tax/payments").param("year","2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.payments").isEmpty())
            .andExpect(jsonPath("$.data.suggestions[0].suggested_date").value("2027-03-31"))
            .andExpect(jsonPath("$.data.suggestions[1].suggested_date").value("2026-08-31"));
        verify(mapper,never()).savePayment(any());
    }
    String paymentsBody(String planned,String actual) {
        return "{\"payments\":[{\"tax_pay_se_cd\":\"FINAL\",\"planned_date\":\""+planned+"\",\"planned_amount\":5000000"+actual+"}]}";
    }
    @Test void paymentNaturalUpsertKeepsAttributionYearAndGeneratedKey() throws Exception {
        mvc.perform(put("/api/finance/tax/payments").param("year","2026").contentType("application/json")
            .content(paymentsBody("2027-03-31",""))).andExpect(status().isOk());
        verify(mapper).savePayment(argThat(w->w.year().equals("2026") && w.input().tax_pay_se_cd().equals("FINAL")));
        verify(mapper).auditPayment(argThat(a->a.id()==55 && a.before()==null));
        verifyNoInteractions(executions);
    }
    @Test void actualPaymentDelegatesToSharedCompletionUsingTaxIdNotAmountMatching() throws Exception {
        mvc.perform(put("/api/finance/tax/payments?year=2026").contentType("application/json")
            .content(paymentsBody("2026-10-15",",\"actual_date\":\"2026-10-16\",\"actual_amount\":5000000"))).andExpect(status().isOk());
        verify(executions).completeTax(55,LocalDate.of(2026,10,16),5000000,null);
    }
    @ParameterizedTest @ValueSource(strings={"planned","actual","original"})
    void eachAffectedClosedMonthRejectsWholeRequestBeforeWrites(String branch) throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        String planned=branch.equals("planned")?"2026-09-30":"2026-10-15";
        String actual=branch.equals("actual")?",\"actual_date\":\"2026-09-30\",\"actual_amount\":5000000":"";
        if(branch.equals("original")) when(mapper.lockPayments("2026")).thenReturn(List.of(new Payment(55L,"FINAL",LocalDate.of(2026,9,30),5000000L,null,null)));
        mvc.perform(put("/api/finance/tax/payments?year=2026").contentType("application/json").content(paymentsBody(planned,actual)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).savePayment(any());verify(mapper,never()).auditPayment(any());verifyNoInteractions(executions);
    }
    @Test void paidRecordCannotChangePlanOrActual() throws Exception {
        var before=new Payment(55L,"FINAL",LocalDate.of(2026,10,15),5000000L,LocalDate.of(2026,10,16),5000000L);
        when(mapper.lockPayments("2026")).thenReturn(List.of(before));
        mvc.perform(put("/api/finance/tax/payments?year=2026").contentType("application/json").content(paymentsBody("2026-10-20","")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));
        verify(mapper,never()).savePayment(any());assertThat(before.planned_date()).isEqualTo(LocalDate.of(2026,10,15));
    }
    @ParameterizedTest @ValueSource(strings={"{\"payments\":[{\"tax_pay_se_cd\":\"OTHER\"}]}","{\"payments\":[{\"tax_pay_se_cd\":\"FINAL\",\"planned_date\":\"2026-02-30\",\"planned_amount\":0}]}","{\"payments\":[{\"tax_pay_se_cd\":\"FINAL\",\"planned_date\":\"2026-10-15\",\"planned_amount\":-1}]}","{\"payments\":[{\"tax_pay_se_cd\":\"FINAL\",\"planned_date\":\"2026-10-15\",\"planned_amount\":0,\"actual_amount\":0}]}","{}"})
    void paymentValidationRejectsBeforeWrite(String body) throws Exception {
        mvc.perform(put("/api/finance/tax/payments?year=2026").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper,never()).savePayment(any());
    }
    @Test void duplicateNaturalKeysRejected() throws Exception {
        String row="{\"tax_pay_se_cd\":\"FINAL\",\"planned_date\":\"2026-10-15\",\"planned_amount\":0}";
        mvc.perform(put("/api/finance/tax/payments?year=2026").contentType("application/json").content("{\"payments\":["+row+","+row+"]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("tax_pay_se_cd"));
        verify(mapper,never()).savePayment(any());
    }
    @Test void unexpectedFailureDoesNotLeakSql() throws Exception {
        when(mapper.payments("2026")).thenThrow(new IllegalStateException("SELECT secret FROM internal"));
        mvc.perform(get("/api/finance/tax/payments?year=2026")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
}
