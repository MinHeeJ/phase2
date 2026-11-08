package com.sicost.adjustments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.adjustments.AdjustmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=AdjustmentController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,AdjustmentService.class,CurrentUser.class,PermissionService.class,SecurityConfiguration.class})
class AdjustmentApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AdjustmentMapper mapper;
    @MockBean Clock clock;
    @MockBean AuthorizationService authorization;
    @MockBean SessionService sessions;
    @Autowired org.springframework.web.context.WebApplicationContext application;
    Row current;
    Input inserted;
    @BeforeEach void setup() {
        mvc=com.sicost.support.AuthenticatedMvc.build(application,sessions);
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        current=row("ACTIVE","2026-10",100);
        when(mapper.monthStatus("2026-08")).thenReturn("CLOSED");
        when(mapper.row(7)).thenAnswer(i->current);
        when(mapper.lockRow(7)).thenAnswer(i->current);
        when(mapper.insert(any())).thenAnswer(i->{inserted=i.getArgument(0,Write.class).input(); return 7L;});
        when(mapper.cancel(any())).thenAnswer(i->{current=row("CANCELLED","2026-10",100);return 1;});
        when(mapper.list(any())).thenAnswer(i->List.of(current));
        when(mapper.count(any())).thenReturn(23L);
    }
    Row row(String status,String apply,long amount) {
        return new Row(7,"2026-08",apply,"REVENUE",amount,"원본 사유",status,
            OffsetDateTime.parse("2026-10-29T09:00:00+09:00"),status.equals("CANCELLED")?OffsetDateTime.parse("2026-10-29T09:01:00+09:00"):null,null);
    }
    Map<String,Object> input() {
        return new LinkedHashMap<>(Map.of("originalMonth","2026-08","applyMonth","2026-10","type","REVENUE","amount",100,"reason","원본 사유"));
    }
    org.springframework.test.web.servlet.ResultActions postBody(String path,Object body) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(json.writeValueAsString(body)));
    }
    void unchanged() { verify(mapper,never()).insert(any());verify(mapper,never()).cancel(any());verify(mapper,never()).audit(any()); }
    @Test void anonymousCreateReturnsBusinessResultAndAuditsUnderGlobalLock() throws Exception {
        postBody("/api/closing/adjustments",input()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.originalMonth").value("2026-08")).andExpect(jsonPath("$.data.applyMonth").value("2026-10"))
            .andExpect(jsonPath("$.data.amount").value(100)).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        var order=inOrder(mapper);order.verify(mapper).lockMonths();order.verify(mapper).monthStatus("2026-10");
        verify(mapper).audit(argThat(a->a.id()==7 && Long.valueOf(7).equals(a.actor()) && a.action().equals("INSERT")));
        verify(mapper).access(any());
    }
    @Test void anonymousCancelPreservesOriginalBusinessFields() throws Exception {
        mvc.perform(post("/api/closing/adjustments/7/cancel").contentType("application/json").content(json.writeValueAsBytes(Map.of("reason","  입력 취소  ")))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED")).andExpect(jsonPath("$.data.amount").value(100))
            .andExpect(jsonPath("$.data.reason").value("원본 사유")).andExpect(jsonPath("$.data.cancelledAt").exists());
        verify(mapper).cancel(argThat(c->c.reason().equals("입력 취소") && Long.valueOf(7).equals(c.actor())));
        verify(mapper).audit(argThat(a->a.before().contains("ACTIVE")&&a.after().contains("CANCELLED")&&a.after().contains("입력 취소")));
    }
    @Test void listIncludesCancelledAndUsesSameMonthForCountAndPage() throws Exception {
        current=row("CANCELLED","2026-10",100);
        mvc.perform(get("/api/closing/adjustments").param("year","2026").param("month","2026-10").param("page","1").param("size","7"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(23)).andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(7)).andExpect(jsonPath("$.data.items[0].status").value("CANCELLED"));
        Query q=new Query(2026,"2026-10",1,7,7);verify(mapper).list(q);verify(mapper).count(q);
        verify(mapper,never()).lockMonths();
    }
    @Test void listDefaultsAreZeroAndTwenty() throws Exception {
        mvc.perform(get("/api/closing/adjustments").param("year","2026")).andExpect(status().isOk());
        verify(mapper).list(new Query(2026,null,0,20,0));
    }
    @ParameterizedTest @ValueSource(strings={"2026-09","2026-11"})
    void nonCurrentApplyMonthRejected(String month) throws Exception {
        var b=input();b.put("applyMonth",month);postBody("/api/closing/adjustments",b).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CURRENT_MONTH_REQUIRED"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"originalMonth","applyMonth"})
    void malformedMonthDoesNotWrite(String field) throws Exception {
        var b=input();b.put(field,"2026-13");postBody("/api/closing/adjustments",b).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_MONTH"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={""," ","\t"})
    void createBlankReasonRejected(String reason) throws Exception {
        var b=input();b.put("reason",reason);postBody("/api/closing/adjustments",b).andExpect(status().isBadRequest());unchanged();
    }
    @ParameterizedTest @ValueSource(strings={""," ","\t"})
    void cancelBlankReasonRejected(String reason) throws Exception {
        postBody("/api/closing/adjustments/7/cancel",Map.of("reason",reason)).andExpect(status().isBadRequest());unchanged();
    }
    @Test void reasonOverFiveHundredRejectedForBothWrites() throws Exception {
        var b=input();b.put("reason","가".repeat(501));postBody("/api/closing/adjustments",b).andExpect(status().isBadRequest());
        postBody("/api/closing/adjustments/7/cancel",Map.of("reason","가".repeat(501))).andExpect(status().isBadRequest());unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"0","1.5","\"100\"","9223372036854775808","null"})
    void amountMustBeNonzeroInt64(String value) throws Exception {
        String body=json.writeValueAsString(input()).replace("\"amount\":100","\"amount\":"+value);
        mvc.perform(post("/api/closing/adjustments").contentType("application/json").content(body)).andExpect(status().isBadRequest());unchanged();
    }
    @Test void createCurrentClosedMonthIsImmutable() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        postBody("/api/closing/adjustments",input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();
    }
    @Test void originalOpenMonthRejected() throws Exception {
        when(mapper.monthStatus("2026-08")).thenReturn("OPEN");
        postBody("/api/closing/adjustments",input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORIGINAL_MONTH_NOT_CLOSED"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"2026-10","2026-11"})
    void originalMustPrecedeApplyMonth(String month) throws Exception {
        var b=input();b.put("originalMonth",month);
        postBody("/api/closing/adjustments",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings={"projectId","developerId","originalAdjustmentId"})
    void optionalReferencesMustBeExistingPositiveIds(String field) throws Exception {
        var b=input();b.put(field,-1);postBody("/api/closing/adjustments",b).andExpect(status().isBadRequest());
        b.put(field,99);postBody("/api/closing/adjustments",b).andExpect(status().isBadRequest());unchanged();
    }
    @Test void closedOriginalCorrectionAlwaysInsertsTwoNewLinkedRows() throws Exception {
        current=row("ACTIVE","2026-09",100);when(mapper.row(88)).thenReturn(current);
        var b=input();b.put("originalAdjustmentId",88);b.put("amount",-100);
        postBody("/api/closing/adjustments",b).andExpect(status().isOk());assertThat(inserted.originalAdjustmentId()).isEqualTo(88);
        b.put("amount",70);postBody("/api/closing/adjustments",b).andExpect(status().isOk());
        verify(mapper,times(2)).insert(any());verify(mapper,never()).cancel(any());assertThat(current.amount()).isEqualTo(100);
    }
    @Test void duplicateCancelIsConflictAndNoMutation() throws Exception {
        current=row("CANCELLED","2026-10",100);
        postBody("/api/closing/adjustments/7/cancel",Map.of("reason","취소")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));unchanged();
    }
    @Test void closedApplyCancelIsConflictAndNoMutation() throws Exception {
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        postBody("/api/closing/adjustments/7/cancel",Map.of("reason","취소")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();
    }
    @Test void pastOpenApplyCannotCancel() throws Exception {
        current=row("ACTIVE","2026-09",100);
        postBody("/api/closing/adjustments/7/cancel",Map.of("reason","취소")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CURRENT_MONTH_REQUIRED"));unchanged();
    }
    @Test void missingCancelIsNotFound() throws Exception {
        postBody("/api/closing/adjustments/99/cancel",Map.of("reason","취소")).andExpect(status().isNotFound());unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"-1","0","abc","9223372036854775808"})
    void invalidPathIdIsBadRequest(String id) throws Exception {
        postBody("/api/closing/adjustments/"+id+"/cancel",Map.of("reason","취소")).andExpect(status().isBadRequest());unchanged();
    }
    @Test void mobileHintBlocksBothWritesBeforeMutation() throws Exception {
        for(String path:List.of("/api/closing/adjustments","/api/closing/adjustments/7/cancel")) {
            mvc.perform(post(path).header("Sec-CH-UA-Mobile","?1").contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PC_ONLY"));
        } verifyNoInteractions(mapper);
    }
    @Test void mobileUserAgentBlocksWritesButNotReads() throws Exception {
        mvc.perform(post("/api/closing/adjustments").header("User-Agent","Mozilla iPhone").contentType("application/json").content("{}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/closing/adjustments").header("Sec-CH-UA-Mobile","?1").param("year","2026")).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"page=-1","size=0","size=101","year=0","year=abc","month=2025-10"})
    void invalidListFiltersAreRejected(String parameter) throws Exception {
        String[] p=parameter.split("=");var request=get("/api/closing/adjustments");
        if(!p[0].equals("year"))request.param("year","2026");request.param(p[0],p[1]);
        mvc.perform(request).andExpect(status().isBadRequest());verifyNoInteractions(mapper);
    }
    @Test void unknownBodyFieldsCannotTurnCreateIntoUpdate() throws Exception {
        var b=input();b.put("id",7);postBody("/api/closing/adjustments",b).andExpect(status().isBadRequest());
        postBody("/api/closing/adjustments/7/cancel",Map.of("reason","취소","month","2026-13")).andExpect(status().isBadRequest());unchanged();
    }
    @Test void unexpectedFailureDoesNotExposeSql() throws Exception {
        when(mapper.list(any())).thenThrow(new IllegalStateException("private SQL"));
        mvc.perform(get("/api/closing/adjustments").param("year","2026")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
    @Test void durableContractContainsOwnedOperations() throws Exception {
        var resource=new org.springframework.core.io.ClassPathResource("contracts/openapi.yaml");
        String contract=new String(resource.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(contract).contains("operationId: listClosingAdjustments","operationId: createClosingAdjustment","operationId: cancelClosingAdjustment");
    }
}
