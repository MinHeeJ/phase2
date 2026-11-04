package com.sicost.closingops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.apiops.*;
import com.sicost.billingsops.BillingGapService;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.costallocation.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import static com.sicost.closingops.ClosingModels.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=ClosingController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,ClosingService.class,ClosingHistoryService.class,
    ClosingCheckService.class,ClosingCheckWriter.class,CurrentUser.class,PermissionService.class,ClosingApiTest.TransactionConfig.class})
class ClosingApiTest {
    @TestConfiguration
    static class TransactionConfig {
        @Bean PlatformTransactionManager transactions() {
            // Mapper mocks need no resource transaction, but after-commit callbacks need Spring synchronization.
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() {return new Object();}
                @Override protected void doBegin(Object transaction,TransactionDefinition definition) {}
                @Override protected void doCommit(DefaultTransactionStatus status) {}
                @Override protected void doRollback(DefaultTransactionStatus status) {}
            };
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ClosingMapper mapper;
    @MockBean ClosingQueryService stored;
    @MockBean ClosingQueryMapper evidence;
    @MockBean ClosingSnapshotService snapshots;
    @MockBean CostAllocationService costs;
    @MockBean BillingGapService gaps;
    @MockBean Clock clock;

    List<Check> source;
    String status;
    List<String> closed;
    List<CheckRun> runs;
    @BeforeEach void setup() {
        status="OPEN";closed=new ArrayList<>();runs=new ArrayList<>();
        source=new ArrayList<>();
        for(String code:List.of("SALARY_UPLOAD","MM_OVER","DISPATCH_ATT","BILL_UNCONFIRMED","TAX_INVOICE_NO","FIN_ENTRY","PART_SETTLE","PAYMENT"))source.add(new Check(code,"OK",0,"정상"));
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));

        when(mapper.status("2026-10")).thenAnswer(i->status);
        when(mapper.closedMonths()).thenAnswer(i->List.copyOf(closed));
        when(mapper.sourceChecks("2026-10")).thenAnswer(i->List.copyOf(source));
        when(costs.calculate("2026-10",null)).thenReturn(new CostAllocationModels.Allocation("2026-10","2026-10",false,"원",List.of(),List.of(),0));
        when(gaps.filtered(any())).thenReturn(List.of());
        doAnswer(i->{runs.add(i.getArgument(0));return null;}).when(mapper).archiveChecks(any());
        when(mapper.transition(any())).thenAnswer(i->{var t=i.getArgument(0,Transition.class);status=t.action().equals("CLOSE")?"CLOSED":"OPEN";return 1;});
        when(evidence.snapshotEvidence("2026-10")).thenReturn("{\"allocation\":{},\"profit\":{},\"details\":{},\"tax\":{},\"cash\":{}}");
        when(snapshots.components("2026-10")).thenReturn(new Components("2026-10",List.of(),Map.of(),List.of(),0,100,100,List.of()));
    }
    org.springframework.test.web.servlet.ResultActions confirm(String body) throws Exception {
        return mvc.perform(post("/api/closing/confirm").contentType("application/json").content(body));
    }
    org.springframework.test.web.servlet.ResultActions reopen(String reason) throws Exception {
        return mvc.perform(post("/api/closing/reopen").contentType("application/json").content(json.writeValueAsString(Map.of("month","2026-10","reason",reason))));
    }
    void isClosed() {status="CLOSED";closed.add("2026-10");}
    @Test void checkAnonymousSuccessStoresNineAndArchivesEveryExecutionWithoutChangingSources() throws Exception {
        for(int i=0;i<2;i++)mvc.perform(post("/api/closing/check").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.checks.length()").value(9)).andExpect(jsonPath("$.data.checkedAt").exists());
        verify(mapper,times(2)).saveChecks(any());assertThat(runs).hasSize(2);
        assertThat(runs.get(0).checks()).hasSize(9);assertThat(status).isEqualTo("OPEN");verify(mapper,never()).profit(any());
    }
    @Test void missingSalaryIsPersistedFail() throws Exception {
        source.set(0,new Check("SALARY_UPLOAD","FAIL",1,"급여 누락"));
        mvc.perform(post("/api/closing/check").param("month","2026-10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checks[0].status").value("FAIL"));
    }
    @Test void failedPrecheckSurvivesRefusedConfirm() throws Exception {
        source.set(0,new Check("SALARY_UPLOAD","FAIL",1,"급여 누락"));
        confirm("{\"month\":\"2026-10\",\"ackWarnings\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSE_CHECK_FAILED"));
        assertThat(runs).hasSize(1);verify(mapper,never()).profit(any());verify(mapper,never()).historyWrite(any());
    }
    @Test void warningRequiresAcknowledgementOnThisRequest() throws Exception {
        source.set(1,new Check("MM_OVER","WARN",1,"1.01"));
        confirm("{\"month\":\"2026-10\",\"ackWarnings\":false}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSE_CHECK_FAILED"));
        verify(mapper,never()).profit(any());assertThat(runs).hasSize(1);
    }
    @Test void acknowledgedWarningCanCloseAnonymouslyAndAuditAllOutputs() throws Exception {
        source.set(1,new Check("MM_OVER","WARN",1,"1.01"));
        confirm("{\"month\":\"2026-10\",\"ackWarnings\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.actor").value("시스템 처리"));
        verify(mapper).profit(any());verify(mapper).tax(any());verify(mapper).cash(any());verify(mapper).audit(any());verify(mapper).access(any());
        assertThat(runs.get(0).actor()).isNull();
    }
    @Test void falseIsValidWhenEveryCheckIsOk() throws Exception {
        confirm("{\"month\":\"2026-10\",\"ackWarnings\":false}").andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"{\"month\":\"2026-10\"}","{\"month\":\"2026-10\",\"ackWarnings\":null}","{\"month\":\"2026-10\",\"ackWarnings\":\"true\"}","{\"month\":\"2026-10\",\"ackWarnings\":1}"})
    void booleanMustBePresentAndStrict(String body) throws Exception {
        confirm(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ackWarnings"));verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings={"2026-13","2026-1","0000-01","2026-11"})
    void checkInvalidAndFutureMonthsDoNotStore(String month) throws Exception {
        mvc.perform(post("/api/closing/check").param("month",month)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));verifyNoInteractions(mapper);
    }
    @Test void confirmInvalidMonthDoesNotStore() throws Exception {
        confirm("{\"month\":\"2026-13\",\"ackWarnings\":true}").andExpect(status().isBadRequest());verifyNoInteractions(mapper);
    }
    @Test void closedCheckIsImmutable() throws Exception {
        isClosed();mvc.perform(post("/api/closing/check").param("month","2026-10")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));verify(mapper,never()).saveChecks(any());
    }
    @Test void duplicateConfirmIsAlreadyClosedAndDoesNotSnapshotAgain() throws Exception {
        isClosed();confirm("{\"month\":\"2026-10\",\"ackWarnings\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CLOSED"));verify(mapper,never()).profit(any());
    }
    @Test void skippedMonthIsOrderConflict() throws Exception {
        closed.add("2026-08");confirm("{\"month\":\"2026-10\",\"ackWarnings\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSE_ORDER"));verify(mapper,never()).saveChecks(any());
    }
    @Test void gapsInClosedMonthsAreRefused() throws Exception {
        closed.addAll(List.of("2026-07","2026-09"));confirm("{\"month\":\"2026-10\",\"ackWarnings\":true}").andExpect(status().isConflict());
    }
    @Test void reopenLatestAnonymouslyWithdrawsOnlyCloseAndAuditsReason() throws Exception {
        isClosed();reopen("  정정 필요  ").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN"));
        verify(mapper).deleteProfit("2026-10");verify(mapper).deleteAllocations("2026-10");verify(mapper).clearCash("2026-10");
        verify(mapper).historyWrite(argThat(t->t.reason().equals("정정 필요")&&t.actor()==null));
    }
    @Test void reopenOpenIsOrderConflict() throws Exception {reopen("정정").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSE_ORDER"));verify(mapper,never()).deleteProfit(any());}
    @Test void previousClosedCannotReopen() throws Exception {isClosed();closed.add("2026-11");reopen("정정").andExpect(status().isConflict());verify(mapper,never()).deleteProfit(any());}
    @Test void activeReferenceBlocksReopen() throws Exception {
        isClosed();when(evidence.activeReferences("2026-10")).thenReturn(1L);reopen("정정").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADJUSTMENT_REFERENCE_EXISTS"));verify(mapper,never()).deleteProfit(any());
    }
    @Test void missingSnapshotBlocksReopen() throws Exception {
        isClosed();when(stored.getClosing("2026-10")).thenThrow(ApiException.conflict("MISSING_CLOSED_SNAPSHOT","누락","month"));
        reopen("정정").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MISSING_CLOSED_SNAPSHOT"));verify(mapper,never()).deleteProfit(any());
    }
    @ParameterizedTest @ValueSource(strings={" ",""}) void blankReopenReasonNeverMutates(String reason) throws Exception {reopen(reason).andExpect(status().isBadRequest());verifyNoInteractions(mapper);}
    @Test void tooLongReasonNeverMutates() throws Exception {reopen("가".repeat(501)).andExpect(status().isBadRequest());verifyNoInteractions(mapper);}
    @Test void historyEmptyIsEmptyItems() throws Exception {
        mvc.perform(get("/api/closing/history").param("year","2026")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(20));
    }
    @Test void historyUsesContractPagingAndStoredActorTimestampReason() throws Exception {
        when(mapper.history(any())).thenReturn(List.of(new HistoryRow(2,"2026-10","REOPEN",LocalDateTime.of(2026,10,30,10,0),"시스템 처리","정정"),new HistoryRow(1,"2026-10","CLOSE",LocalDateTime.of(2026,10,30,9,0),"시스템 처리",null)));
        when(mapper.historyCount(any())).thenReturn(2L);
        mvc.perform(get("/api/closing/history").param("year","2026").param("size","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].action").value("REOPEN")).andExpect(jsonPath("$.data.items[0].reason").value("정정"));
        verify(mapper).history(argThat(q->q.page()==0&&q.size()==2&&q.offset()==0));
    }
    @Test void invalidHistorySizeDoesNotQuery() throws Exception {mvc.perform(get("/api/closing/history").param("year","2026").param("size","101")).andExpect(status().isBadRequest());verifyNoInteractions(mapper);}
    @Test void previewReturnsScalarContract() throws Exception {
        when(snapshots.preview("2026-10")).thenReturn(new Preview("2026-10",1,2,3,4,List.of("재무 확인")));
        mvc.perform(get("/api/closing/preview").param("month","2026-10")).andExpect(status().isOk()).andExpect(jsonPath("$.data.profit").value(1)).andExpect(jsonPath("$.data.cash").value(4));verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings={"check","confirm","reopen"})
    void mobileWritesReturnPcOnlyBeforeAnyMutation(String operation) throws Exception {
        mvc.perform(post("/api/closing/"+operation).param("month","2026-10").header("Sec-CH-UA-Mobile","?1").contentType("application/json").content("{\"month\":\"2026-10\",\"ackWarnings\":true,\"reason\":\"정정\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PC_ONLY"));verifyNoInteractions(mapper);
    }
    @Test void unexpectedDatabaseErrorIsSanitized() throws Exception {
        when(mapper.history(any())).thenThrow(new IllegalStateException("private SQL"));
        mvc.perform(get("/api/closing/history").param("year","2026")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
}
