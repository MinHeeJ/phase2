package com.sicost.salesparts;

import static com.sicost.salesparts.SalesPartModels.*;
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
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers=SalesPartController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,SalesPartService.class,CurrentUser.class,PermissionService.class,SalesPartApiTest.Time.class})
class SalesPartApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean SalesPartMapper mapper;
    PartRow hq=new PartRow("1","본사","Y","Y",0,0,0);
    PartRow normal=new PartRow("2","영업 파트","N","Y",1,0,0);
    PartRow inactive=new PartRow("3","중지 파트","N","N",2,0,0);
    SalesPartStandard standard(String id,String begin,String end,String rate,String status) {return new SalesPartStandard(id,begin,end,"파트장",new BigDecimal(rate),1500000,status);}
    List<SalesPartStandard> history() {return List.of(standard("3","2026-11",null,"45","예정"),standard("2","2026-10","2026-10","40","적용 중"),standard("1","2026-01","2026-09","35","지난 기준"));}
    @BeforeEach void setup() {
        when(mapper.parts(any())).thenReturn(List.of(hq,normal,inactive));when(mapper.partCount()).thenReturn(3L);
        when(mapper.part(eq(1L),any())).thenReturn(hq);when(mapper.part(eq(2L),any())).thenReturn(normal);when(mapper.part(eq(3L),any())).thenReturn(inactive);
        when(mapper.standards(anyLong(),anyString())).thenReturn(history());when(mapper.lastClosed()).thenReturn("2026-09");
        when(mapper.insertPart(any())).thenReturn(2L);when(mapper.insertStandard(any())).thenReturn(4L);
    }
    Map<String,Object> valid() {return new LinkedHashMap<>(Map.of("bsn_part_nm","새 파트","aply_bgn_ym","2026-11","ldr_nm","파트장","prft_dstrb_rt",40,"bsn_fee_amt",1500000));}
    ResultActions create(Map<String,Object> body)throws Exception {return mvc.perform(post("/api/sales-parts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    void untouched() {verify(mapper,never()).insertPart(any());verify(mapper,never()).insertStandard(any());verify(mapper,never()).appendHistory(any());}
    @Test void publicListIncludesThreePartsAndHeadquartersCurrentValues()throws Exception {
        mvc.perform(get("/api/sales-parts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(3)).andExpect(jsonPath("$.data.items[0].hq_yn").value("Y")).andExpect(jsonPath("$.data.items[1].currentStandard.prft_dstrb_rt").value(40)).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(50));
    }
    @Test void anonymousListRetainsDisabledParts()throws Exception {mvc.perform(get("/api/sales-parts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[2].use_yn").value("N"));}
    @Test void futureStandardIsNeverCurrent()throws Exception {mvc.perform(get("/api/sales-parts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[1].currentStandard.aply_bgn_ym").value("2026-10")).andExpect(jsonPath("$.data.items[1].scheduledChanges[0].prft_dstrb_rt").value(45));}
    @Test void futureOnlyLeavesCurrentNull()throws Exception {when(mapper.standards(anyLong(),anyString())).thenReturn(List.of(history().get(0)));mvc.perform(get("/api/sales-parts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].currentStandard").isEmpty()).andExpect(jsonPath("$.data.items[0].scheduledChanges.length()").value(1));}
    @Test void listHasNoWriteSideEffects()throws Exception {mvc.perform(get("/api/sales-parts")).andExpect(status().isOk());untouched();}
    @ParameterizedTest @ValueSource(strings={"size=0","size=30","page=0","sort=scheduledChanges,asc","sort=bsn_part_nm,up","sort=bsn_part_nm;drop,asc"})
    void invalidListQueryIs400(String query)throws Exception {mvc.perform(get("/api/sales-parts?"+query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(query.substring(0,query.indexOf('='))));untouched();}
    @Test void allowedSortIsStableAndUsesSeoulDate()throws Exception {mvc.perform(get("/api/sales-parts?sort=peopleCount,desc&page=2")).andExpect(status().isOk());verify(mapper).parts(argThat(q->q.offset()==50&&q.month().equals("2026-10")&&q.today().equals(LocalDate.of(2026,10,22))&&q.order().equals("\"peopleCount\" desc, bsn_part_id ASC")));}
    @Test void detailReturnsDescendingHistoryPeriodsAndStatuses()throws Exception {mvc.perform(get("/api/sales-parts/2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.standards.length()").value(3)).andExpect(jsonPath("$.data.standards[0].status").value("예정")).andExpect(jsonPath("$.data.standards[1].aply_end_ym").value("2026-10")).andExpect(jsonPath("$.data.standards[2].status").value("지난 기준")).andExpect(jsonPath("$.data.lastClosedMonth").value("2026-09")).andExpect(jsonPath("$.data.firstAllowedMonth").value("2026-10"));}
    @Test void anonymousDisabledDetailWorks()throws Exception {mvc.perform(get("/api/sales-parts/3")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N"));}
    @Test void missingDetailIs404()throws Exception {mvc.perform(get("/api/sales-parts/999")).andExpect(status().isNotFound());untouched();}
    @Test void detailSeparatesScheduledFromEffective()throws Exception {mvc.perform(get("/api/sales-parts/2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentStandard.prft_dstrb_rt").value(40)).andExpect(jsonPath("$.data.scheduledChanges[0].status").value("예정"));}
    @Test void detailDoesNotMutateHistoryOrClosing()throws Exception {mvc.perform(get("/api/sales-parts/2")).andExpect(status().isOk());untouched();}
    @Test void noClosingDoesNotInventMonth()throws Exception {when(mapper.lastClosed()).thenReturn(null);mvc.perform(get("/api/sales-parts/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.lastClosedMonth").isEmpty()).andExpect(jsonPath("$.data.firstAllowedMonth").isEmpty());}
    @Test void publicCreateUsesGeneratedKeysThenStandardAndTwoAudits()throws Exception {
        mvc.perform(post("/api/sales-parts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_id").value("2"))
            .andExpect(jsonPath("$.data.hq_yn").value("N")).andExpect(jsonPath("$.data.use_yn").value("Y"));
        var order=inOrder(mapper);order.verify(mapper).insertPart(argThat(w->w.actor()==null));order.verify(mapper).insertStandard(argThat(w->w.partId()==2&&w.input().aply_bgn_ym().equals("2026-11")&&w.actor()==null));
        order.verify(mapper).appendHistory(argThat(h->h.tableName().equals("mst.bsn_part")&&h.recordId().equals("2")));
        order.verify(mapper).appendHistory(argThat(h->h.tableName().equals("mst.bsn_part_std_hist")&&h.recordId().equals("4")&&h.after().contains("2026-11")&&h.after().contains("파트장")&&h.after().contains("40")&&h.after().contains("1500000")));
    }
    @ParameterizedTest @CsvSource({"bsn_part_nm,''","ldr_nm,''","aply_bgn_ym,2026-13","prft_dstrb_rt,120","prft_dstrb_rt,-1","prft_dstrb_rt,1.001","bsn_fee_amt,0","bsn_fee_amt,1.5"})
    void invalidFieldsNeverWrite(String field,String value)throws Exception {var body=valid();body.put(field,Set.of("bsn_fee_amt","prft_dstrb_rt").contains(field)?new BigDecimal(value):value);create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"bsn_part_nm","ldr_nm","aply_bgn_ym","prft_dstrb_rt","bsn_fee_amt"})
    void requiredFields(String field)throws Exception {var body=valid();body.remove(field);create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @CsvSource({"bsn_part_nm,31","ldr_nm,51"})
    void excessiveLengths(String field,int length)throws Exception {var body=valid();body.put(field,"가".repeat(length));create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(ints={0,100})
    void percentBoundariesAndMaximumNamesAccepted(int rate)throws Exception {var body=valid();body.put("prft_dstrb_rt",rate);body.put("bsn_part_nm","가".repeat(30));body.put("ldr_nm","나".repeat(50));create(body).andExpect(status().isOk());}
    @Test void duplicateIncludingDisabledConflicts()throws Exception {when(mapper.nameExists(anyString())).thenReturn(true);create(valid()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME")).andExpect(jsonPath("$.message").value("같은 이름의 파트가 이미 있습니다."));untouched();}
    @Test void concurrentDuplicateTranslated()throws Exception {when(mapper.insertPart(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("unique"));create(valid()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME"));verify(mapper,never()).insertStandard(any());}
    @Test void closedMonthRefusesBeforeAnyInsert()throws Exception {var body=valid();body.put("aply_bgn_ym","2026-09");create(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")).andExpect(jsonPath("$.field").value("aply_bgn_ym"));untouched();}
    @Test void noClosingAllowsExplicitPastMonth()throws Exception {when(mapper.lastClosed()).thenReturn(null);var body=valid();body.put("aply_bgn_ym","2025-01");create(body).andExpect(status().isOk());}
    @Test void rejectsUnapprovedLeaderAccountField()throws Exception {var body=valid();body.put("ldr_user_id",1);create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ldr_user_id"));untouched();}
    @ParameterizedTest
    @ValueSource(strings={"null","[]","true","{","{} {}"})
    void malformedCreateReturnsFlatErrorWithoutWriting(String body)throws Exception {
        mvc.perform(post("/api/sales-parts")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        untouched();
    }
    @ParameterizedTest
    @ValueSource(strings={"9223372036854775808","-9223372036854775809","\"1500000\"","true","null"})
    void unsafeFeeIsRejectedBeforePersistence(String fee)throws Exception {
        String body=json.writeValueAsString(valid()).replace("1500000",fee);
        mvc.perform(post("/api/sales-parts")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("bsn_fee_amt"));
        untouched();
    }
    @Test void maximumBigintFeeAndTwoDecimalRateRemainExact()throws Exception {
        var body=valid();
        body.put("bsn_fee_amt",Long.MAX_VALUE);
        body.put("prft_dstrb_rt",new BigDecimal("99.99"));
        create(body).andExpect(status().isOk());
        verify(mapper).insertStandard(argThat(write->
            write.input().bsn_fee_amt()==Long.MAX_VALUE
                &&write.input().prft_dstrb_rt().compareTo(new BigDecimal("99.99"))==0));
    }
    @ParameterizedTest
    @ValueSource(strings={"0","-1","abc","9223372036854775808"})
    void invalidDetailIdentifiersDoNotReachPersistence(String id)throws Exception {
        mvc.perform(get("/api/sales-parts/{id}",id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper,never()).part(anyLong(),any());
        untouched();
    }
    @Test void emptyListRetainsPageContractWithoutWrites()throws Exception {
        when(mapper.parts(any())).thenReturn(Collections.emptyList());
        when(mapper.partCount()).thenReturn(0L);
        mvc.perform(get("/api/sales-parts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.page").value(1));
        untouched();
    }
    @Test void validationCreateZeroFeeHasLiteralPathAndNoPartStandardOrAudit()throws Exception {
        var input=valid();input.put("bsn_fee_amt",0);
        mvc.perform(post("/api/sales-parts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_fee_amt"))
            .andExpect(jsonPath("$.message").isNotEmpty());untouched();
    }
}
