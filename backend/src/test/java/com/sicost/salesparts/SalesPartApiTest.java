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
    PartRow hq=new PartRow("1","본사(직접 영업)","Y","Y",999,0,0);
    PartRow part=new PartRow("2","영업 파트","N","Y",1,0,0);
    PartRow stopped=new PartRow("3","중지 파트","N","N",2,0,0);
    SalesPartStandard current=new SalesPartStandard("2","2026-10","2026-10","홍길동",new BigDecimal("40"),1500000L,"적용 중");
    SalesPartStandard future=new SalesPartStandard("3","2026-11",null,"홍길동",new BigDecimal("45"),1500000L,"예정");
    SalesPartStandard past=new SalesPartStandard("4","2026-01","2026-09","홍길동",new BigDecimal("40"),1500000L,"지난 기준");
    @BeforeEach void setup() {
        when(mapper.list(any())).thenReturn(List.of(hq,part,stopped));when(mapper.count()).thenReturn(3L);
        when(mapper.part(eq("1"),any())).thenReturn(hq);when(mapper.part(eq("2"),any())).thenReturn(part);when(mapper.part(eq("3"),any())).thenReturn(stopped);
        when(mapper.standards(eq("1"),anyString())).thenReturn(List.of(new SalesPartStandard("1","2026-01",null,null,BigDecimal.ZERO,0L,"적용 중")));
        when(mapper.standards(eq("2"),anyString())).thenReturn(List.of(future,current,past));
        when(mapper.standards(eq("3"),anyString())).thenReturn(List.of(current));
        when(mapper.lastClosed()).thenReturn("2026-09");when(mapper.insertPart(any())).thenReturn("2");when(mapper.insertStandard(any())).thenReturn("2");
    }
    Map<String,Object> valid() { return new HashMap<>(Map.of("bsn_part_nm","신규 파트","aply_bgn_ym","2026-11","ldr_nm","홍길동","prft_dstrb_rt",40,"bsn_fee_amt",1500000)); }
    ResultActions create(Map<String,Object> body)throws Exception { return mvc.perform(post("/api/sales-parts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))); }
    void untouched() {verify(mapper,never()).insertPart(any());verify(mapper,never()).insertStandard(any());verify(mapper,never()).appendHistory(any());}
    @Test void anonymousListIncludesHeadquartersCurrentValuesAndStoppedPart()throws Exception {
        mvc.perform(get("/api/sales-parts")).andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.data.items.length()").value(3)).andExpect(jsonPath("$.data.items[0].hq_yn").value("Y"))
            .andExpect(jsonPath("$.data.items[0].currentStandard.bsn_fee_amt").value(0)).andExpect(jsonPath("$.data.items[0].currentStandard.ldr_nm").isEmpty())
            .andExpect(jsonPath("$.data.items[1].currentStandard.prft_dstrb_rt").value(40)).andExpect(jsonPath("$.data.items[2].use_yn").value("N"))
            .andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(50));
        untouched();
    }
    @Test void scheduledValueNeverBecomesCurrentAndQueryUsesSeoulToday()throws Exception {
        mvc.perform(get("/api/sales-parts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[1].scheduledChanges[0].prft_dstrb_rt").value(45))
            .andExpect(jsonPath("$.data.items[1].currentStandard.aply_bgn_ym").value("2026-10"));
        verify(mapper).list(argThat(q->q.month().equals("2026-10")&&q.today().equals(LocalDate.of(2026,10,22))&&q.order().equals("sort_seq ASC, bsn_part_id ASC")));
    }
    @Test void futureOnlyPartSerializesRequiredNullCurrentStandard()throws Exception {
        when(mapper.standards(eq("2"),anyString())).thenReturn(List.of(future));
        var result=mvc.perform(get("/api/sales-parts/2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentStandard").isEmpty()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("data").has("currentStandard")).isTrue();
    }
    @ParameterizedTest @CsvSource({"size,0","size,30","page,0","sort,scheduledChanges:asc","sort,unknown:asc","sort,bsn_part_nm:up","sort,bsn_part_nm;DROP:asc"})
    void invalidListQueryIsFieldError(String key,String value)throws Exception {
        mvc.perform(get("/api/sales-parts").param(key,value.replace(':',','))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(key));untouched();
    }
    @ParameterizedTest @ValueSource(strings={"bsn_part_nm","ldr_nm","prft_dstrb_rt","bsn_fee_amt","aply_bgn_ym","peopleCount","projectCount","use_yn"})
    void everyDisplayedSortableColumnIsServerSortedWithPkTieBreaker(String key)throws Exception {
        mvc.perform(get("/api/sales-parts").param("sort",key+",desc").param("page","2").param("size","20")).andExpect(status().isOk());
        verify(mapper).list(argThat(q->q.offset()==20 && q.order().endsWith("desc NULLS LAST, bsn_part_id ASC")));
    }
    @Test void anonymousStoppedDetailIncludesThreeOrderedPeriodsAndClosingMetadata()throws Exception {
        when(mapper.standards(eq("3"),anyString())).thenReturn(List.of(future,current,past));
        mvc.perform(get("/api/sales-parts/3")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N"))
            .andExpect(jsonPath("$.data.standards.length()").value(3)).andExpect(jsonPath("$.data.standards[0].status").value("예정"))
            .andExpect(jsonPath("$.data.standards[1].status").value("적용 중")).andExpect(jsonPath("$.data.standards[2].status").value("지난 기준"))
            .andExpect(jsonPath("$.data.lastClosedMonth").value("2026-09")).andExpect(jsonPath("$.data.firstAllowedMonth").value("2026-10"));untouched();
    }
    @Test void nonexistentPartIs404()throws Exception {mvc.perform(get("/api/sales-parts/999")).andExpect(status().isNotFound());untouched();}
    @Test void noClosingHistoryReturnsNullAndSeoulCurrentFirstAllowedMonth()throws Exception {
        when(mapper.lastClosed()).thenReturn(null);
        var response=mvc.perform(get("/api/sales-parts/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.firstAllowedMonth").value("2026-10")).andReturn();
        var data=json.readTree(response.getResponse().getContentAsString()).path("data");assertThat(data.has("lastClosedMonth")).isTrue();assertThat(data.get("lastClosedMonth").isNull()).isTrue();
    }
    @Test void anonymousCreateInsertsParentBeforeStandardThenTwoInsertHistories()throws Exception {
        mvc.perform(post("/api/sales-parts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_id").value("2"))
            .andExpect(jsonPath("$.data.hq_yn").value("N")).andExpect(jsonPath("$.data.use_yn").value("Y"));
        var order=inOrder(mapper);order.verify(mapper).insertPart(argThat(w->w.actor()==null));
        order.verify(mapper).insertStandard(argThat(w->w.id().equals("2") && w.actor()==null));
        order.verify(mapper).appendHistory(argThat(h->h.table().equals("mst.bsn_part") && h.actor()==null));
        order.verify(mapper).appendHistory(argThat(h->h.table().equals("mst.bsn_part_std_hist")&&h.after().contains("2026-11부터 · 파트장 홍길동 · 40% · 1,500,000원")));
    }
    @ParameterizedTest @CsvSource({"bsn_part_nm,''","ldr_nm,''","aply_bgn_ym,2026-13","aply_bgn_ym,0000-01"})
    void requiredTextAndMonthAreValidated(String field,String value)throws Exception {var body=valid();body.put(field,value);create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"bsn_part_nm","ldr_nm","prft_dstrb_rt","bsn_fee_amt","aply_bgn_ym"})
    void missingRequiredFieldDoesNotWrite(String field)throws Exception {var body=valid();body.remove(field);create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @CsvSource({"prft_dstrb_rt,120","prft_dstrb_rt,-1","prft_dstrb_rt,40.001","bsn_fee_amt,0","bsn_fee_amt,-1","bsn_fee_amt,1.5"})
    void invalidNumericFieldDoesNotWrite(String field,String value)throws Exception {var body=valid();body.put(field,new BigDecimal(value));create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"bsn_part_nm","ldr_nm"})
    void inputLengthsStayNarrowerThanStorage(String field)throws Exception {var body=valid();body.put(field,"가".repeat(field.equals("bsn_part_nm")?31:51));create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(ints={0,100})
    void percentBoundsAndThirtyCharacterNameAreAccepted(int value)throws Exception {var body=valid();body.put("prft_dstrb_rt",value);body.put("bsn_part_nm","가".repeat(30));create(body).andExpect(status().isOk());}
    @Test void duplicateNameIs409IncludingInactiveNames()throws Exception {when(mapper.nameExists(anyString())).thenReturn(true);create(valid()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME")).andExpect(jsonPath("$.message").value("같은 이름의 파트가 이미 있습니다."));untouched();}
    @Test void databaseUniqueRaceBecomesDuplicateConflict()throws Exception {when(mapper.insertPart(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));create(valid()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME"));verify(mapper,never()).insertStandard(any());}
    @Test void closedMonthRejectsWithoutWrites()throws Exception {var body=valid();body.put("aply_bgn_ym","2026-09");create(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));untouched();}
    @Test void noCloseHistoryRejectsBeforeCurrentMonth()throws Exception {when(mapper.lastClosed()).thenReturn(null);var body=valid();body.put("aply_bgn_ym","2026-09");create(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));untouched();}
    @ParameterizedTest @ValueSource(strings={"hq_yn","sort_seq","ldr_user_id","use_yn"})
    void serverOwnedFieldsCannotBeSupplied(String field)throws Exception {var body=valid();body.put(field,"1");create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
}
