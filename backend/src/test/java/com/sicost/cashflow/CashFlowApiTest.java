package com.sicost.cashflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.billingsops.BillingRevenueQuery;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.cashflow.CashFlowModels.*;

@WebMvcTest(controllers=CashFlowController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,CashFlowService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class})
class CashFlowApiTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean CashFlowMapper mapper;
    @MockBean BillingRevenueQuery billing;
    @MockBean Clock clock;
    Inflow existing;
    @BeforeEach void setup() {
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-30T00:00:00Z"));
        when(mapper.initial()).thenReturn(new Balance("2026-10",100L,null,null,"Y","OPEN"));
        when(mapper.balances()).thenReturn(List.of());
        when(mapper.events()).thenReturn(List.of(new Event(LocalDate.of(2026,10,10),20,LocalDate.of(2026,10,11),10,0,true),new Event(LocalDate.of(2026,10,10),150,LocalDate.of(2026,10,11),40,0,false)));
        when(mapper.inflows()).thenAnswer(i->existing==null?List.of():List.of(existing));
        existing=new Inflow(7L,"2026-10",10,"MISC","수입",LocalDate.of(2026,10,10),null,null);
        when(mapper.inflow(7)).thenAnswer(i->existing);
        when(mapper.insertInflow(any())).thenReturn(8L);
        when(mapper.updateInflow(any())).thenReturn(1);
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
    }
    String body(){return "{\"month\":\"2026-10\",\"amount\":0,\"inflw_se_cd\":\"LOAN\",\"inflw_item_nm\":\"대여금\",\"planned_date\":\"2026-10-15\"}";}
    @Test void separateChainsAndRealXlsx() throws Exception {
        var result=mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","BOTH"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months[9].planned_closing").value(-30))
            .andExpect(jsonPath("$.data.months[9].actual_closing").value(70))
            .andExpect(jsonPath("$.data.months[9].forecast_closing").value(70))
            .andExpect(jsonPath("$.data.months[9].categories[0].planned_outflow").value(150)).andReturn();
        byte[] bytes=Base64.getDecoder().decode(json.readTree(result.getResponse().getContentAsString()).at("/data/export_file/content_base64").asText());
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getRow(13).getCell(2).getNumericCellValue()).isEqualTo(-30);
        }
        verify(mapper,never()).saveOpening(any());
    }
    @Test void openingRead() throws Exception {mvc.perform(get("/api/finance/cashflow/opening").param("month","2026-10")).andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value(100));}
    @Test void openingMissing() throws Exception {when(mapper.initial()).thenReturn(null);mvc.perform(get("/api/finance/cashflow/opening").param("month","2026-10")).andExpect(status().isNotFound());}
    @Test void openingOneTime() throws Exception {mvc.perform(put("/api/finance/cashflow/opening").param("month","2026-10").contentType("application/json").content("{\"amount\":0}")).andExpect(status().isConflict());verify(mapper,never()).saveOpening(any());}
    @Test void openingCreateAudits() throws Exception {when(mapper.initial()).thenReturn(null);mvc.perform(put("/api/finance/cashflow/opening").param("month","2026-10").contentType("application/json").content("{\"amount\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value(0));verify(mapper).lockOpening();verify(mapper).audit(any());}
    @Test void openingClosed() throws Exception {mvc.perform(put("/api/finance/cashflow/opening").param("month","2026-09").contentType("application/json").content("{\"amount\":0}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));}
    @Test void openingMismatch() throws Exception {mvc.perform(put("/api/finance/cashflow/opening").param("month","2026-10").contentType("application/json").content("{\"month\":\"2026-11\",\"amount\":0}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));}
    @Test void inflowsList() throws Exception {mvc.perform(get("/api/finance/cashflow/other-inflows")).andExpect(status().isOk()).andExpect(jsonPath("$.data.month").value("2026-10")).andExpect(jsonPath("$.data.amount").value(10)).andExpect(jsonPath("$.data.items[0].inflw_id").value(7)).andExpect(jsonPath("$.data.total").value(1));}
    @Test void createUsesGeneratedIdentityAndAudits() throws Exception {mvc.perform(post("/api/finance/cashflow/other-inflows").contentType("application/json").content(body())).andExpect(status().isOk()).andExpect(jsonPath("$.data.inflw_id").value(8));verify(mapper).insertInflow(any());verify(mapper).audit(any());}
    @Test void updatePreservesIdentityAndHistory() throws Exception {mvc.perform(put("/api/finance/cashflow/other-inflows").contentType("application/json").content(body().replace("{","{\"inflw_id\":7,"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.inflw_id").value(7));verify(mapper).audit(argThat(a->a.before().contains("수입")&&a.after().contains("대여금")));}
    @Test void updateRequiresIdentity() throws Exception {mvc.perform(put("/api/finance/cashflow/other-inflows").contentType("application/json").content(body())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("inflw_id"));}
    @Test void updateNotFound() throws Exception {when(mapper.inflow(7)).thenReturn(null);mvc.perform(put("/api/finance/cashflow/other-inflows").contentType("application/json").content(body().replace("{","{\"inflw_id\":7,"))).andExpect(status().isNotFound());}
    @Test void originalClosedDateCannotMove() throws Exception {existing=new Inflow(7L,"2026-09",10,"MISC","수입",LocalDate.of(2026,9,10),null,null);mvc.perform(put("/api/finance/cashflow/other-inflows").contentType("application/json").content(body().replace("{","{\"inflw_id\":7,"))).andExpect(status().isConflict());verify(mapper,never()).updateInflow(any());}
    @ParameterizedTest @ValueSource(strings={"POST","PUT"}) void destinationClosed(String method)throws Exception {var request=method.equals("POST")?post("/api/finance/cashflow/other-inflows"):put("/api/finance/cashflow/other-inflows");mvc.perform(request.contentType("application/json").content(body().replace("{","{\"inflw_id\":7,").replace("2026-10","2026-09"))).andExpect(status().isConflict());verify(mapper,never()).updateInflow(any());verify(mapper,never()).insertInflow(any());}
    @ParameterizedTest @ValueSource(strings={"-1","1.5","9223372036854775808","null"}) void invalidWon(String value)throws Exception {mvc.perform(post("/api/finance/cashflow/other-inflows").contentType("application/json").content(body().replace("\"amount\":0","\"amount\":"+value))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));}
    @Test void futureActualRejected()throws Exception {mvc.perform(post("/api/finance/cashflow/other-inflows").contentType("application/json").content(body().replace("}",",\"actual_date\":\"2026-10-31\"}"))).andExpect(status().isBadRequest());}
    @Test void noOpeningIsExplicitConflict()throws Exception {when(mapper.initial()).thenReturn(null);mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","PLAN")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPENING_BALANCE_REQUIRED"));}
    @Test void invalidMode()throws Exception {mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","WRONG")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("mode"));}
    @Test void internalErrorIsSafe()throws Exception {when(mapper.inflows()).thenThrow(new IllegalStateException("private SQL"));mvc.perform(get("/api/finance/cashflow/other-inflows")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));}
    @Test void openingNegativeAmountRejected()throws Exception {mvc.perform(put("/api/finance/cashflow/opening").param("month","2026-10").contentType("application/json").content("{\"amount\":-1}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("amount"));verify(mapper,never()).saveOpening(any());}
    @Test void openingMalformedMonthRejected()throws Exception {mvc.perform(get("/api/finance/cashflow/opening").param("month","2026-13")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"));}
    @ParameterizedTest @ValueSource(strings={"POST","PUT"}) void missingNameIsFieldValidation(String method)throws Exception {var request=method.equals("POST")?post("/api/finance/cashflow/other-inflows"):put("/api/finance/cashflow/other-inflows");mvc.perform(request.contentType("application/json").content(body().replace("{","{\"inflw_id\":7,").replace("\"대여금\"","\" \""))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("inflw_item_nm"));}
    @ParameterizedTest @ValueSource(strings={"POST","PUT"}) void repaymentIsNeverNegativeInflow(String method)throws Exception {var request=method.equals("POST")?post("/api/finance/cashflow/other-inflows"):put("/api/finance/cashflow/other-inflows");mvc.perform(request.contentType("application/json").content(body().replace("{","{\"inflw_id\":7,").replace("\"LOAN\"","\"LOAN_REPAY\""))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("inflw_se_cd"));}
    @Test void futurePlanAllowedButFutureActualIsNull()throws Exception {mvc.perform(post("/api/finance/cashflow/other-inflows").contentType("application/json").content(body().replace("2026-10","2027-01"))).andExpect(status().isOk());mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","BOTH")).andExpect(status().isOk()).andExpect(jsonPath("$.data.months[10].actual_closing").isEmpty());}
    @Test void closedSnapshotWinsOverChangedSources()throws Exception {when(mapper.balances()).thenReturn(List.of(new Balance("2026-10",100L,77L,null,"Y","CLOSED")));mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","BOTH")).andExpect(status().isOk()).andExpect(jsonPath("$.data.months[9].actual_closing").value(77)).andExpect(jsonPath("$.data.months[10].forecast_opening").value(77));}
    @Test void missingClosedSnapshotIsNotRecomputed()throws Exception {when(mapper.balances()).thenReturn(List.of(new Balance("2026-10",100L,null,null,"Y","CLOSED")));mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","BOTH")).andExpect(status().isOk()).andExpect(jsonPath("$.data.months[9].actual_closing").isEmpty()).andExpect(jsonPath("$.data.months[9].warning").value("MISSING_CLOSED_SNAPSHOT"));}
    @Test void missingMinimumDoesNotInventCaution()throws Exception {mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","BOTH")).andExpect(status().isOk()).andExpect(jsonPath("$.data.months[9].warning").isEmpty());}
    @Test void riskPrecedesMinimumCautionAndNamesFirstMonth()throws Exception {when(mapper.events()).thenReturn(List.of(new Event(LocalDate.of(2026,11,10),150,null,0,150,false)));when(mapper.initial()).thenReturn(new Balance("2026-10",100L,null,1000L,"Y","OPEN"));mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","BOTH")).andExpect(status().isOk()).andExpect(jsonPath("$.data.months[9].warning").value("CAUTION")).andExpect(jsonPath("$.data.months[10].warning").value("RISK")).andExpect(jsonPath("$.data.first_risk_month").value("2026-11"));}
    @Test void actualUnlinkedCashAddsOnceToForecast()throws Exception {when(billing.cash(2026)).thenReturn(List.of(new com.sicost.billingsops.BillingModels.Cash(LocalDate.of(2026,10,10),25)));mvc.perform(get("/api/finance/cashflow").param("year","2026").param("mode","BOTH")).andExpect(status().isOk()).andExpect(jsonPath("$.data.months[9].actual_closing").value(95)).andExpect(jsonPath("$.data.months[9].forecast_closing").value(95)).andExpect(jsonPath("$.data.months[9].planned_closing").value(-30));}
}
