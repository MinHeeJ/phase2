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
    PartRow part=new PartRow("2","영업","N","Y",1,0,0);
    SalesPartStandard current=new SalesPartStandard("2026-01","파트장",new BigDecimal("40"),1500000,"2","2026-10","적용 중");
    SalesPartStandard future=new SalesPartStandard("2026-11","파트장",new BigDecimal("45"),1500000,"3",null,"예정");
    @BeforeEach void setup() {
        when(mapper.lastClosed()).thenReturn("2026-09");
        when(mapper.parts(any())).thenReturn(List.of(new PartRow("1","본사","Y","Y",999,0,0),part,new PartRow("3","중지","N","N",2,0,0)));
        when(mapper.count()).thenReturn(3L);
        when(mapper.part(any())).thenReturn(part);
        when(mapper.standards(any())).thenReturn(List.of(future,current));
        when(mapper.insertPart(any())).thenReturn(2L);when(mapper.insertStandard(any())).thenReturn(3L);
    }
    Map<String,Object> input() {return new HashMap<>(Map.of("bsn_part_nm","신규","aply_bgn_ym","2026-11","ldr_nm","파트장","prft_dstrb_rt",40,"bsn_fee_amt",1500000));}
    ResultActions create(Map<String,Object> input)throws Exception {return mvc.perform(post("/api/sales-parts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));}
    void untouched() {verify(mapper,never()).insertPart(any());verify(mapper,never()).insertStandard(any());verify(mapper,never()).appendHistory(any());}
    @Test void listPublicIncludesHeadquartersAndInactiveWithCurrentValues()throws Exception {
        mvc.perform(get("/api/sales-parts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.items[0].hq_yn").value("Y")).andExpect(jsonPath("$.data.items[1].currentStandard.prft_dstrb_rt").value(40)).andExpect(jsonPath("$.data.items[2].use_yn").value("N"));
    }
    @Test @DisplayName("auth happy business: currentStandard excludes future scheduledChanges")
    void listKeepsNovemberScheduledSeparateFromOctober()throws Exception {
        mvc.perform(get("/api/sales-parts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[1].referenceMonth").value("2026-10")).andExpect(jsonPath("$.data.items[1].currentStandard.aply_bgn_ym").value("2026-01")).andExpect(jsonPath("$.data.items[1].scheduledChanges[0].prft_dstrb_rt").value(45));
        verify(mapper).parts(argThat(q->q.month().equals("2026-10")&&q.today().equals(LocalDate.of(2026,10,22))));untouched();
    }
    @Test void listDoesNotWrite()throws Exception {mvc.perform(get("/api/sales-parts")).andExpect(status().isOk());untouched();}
    @Test void sizeZeroHasSizeField()throws Exception {mvc.perform(get("/api/sales-parts?size=0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("size"));untouched();}
    @ParameterizedTest @ValueSource(strings={"page=0","size=30","sort=scheduledChanges,asc","sort=bsn_part_nm,up","sort=bsn_part_nm;DROP,asc"})
    void invalidQueryRejected(String query)throws Exception {mvc.perform(get("/api/sales-parts?"+query)).andExpect(status().isBadRequest());untouched();}
    @Test void nestedSortUsesAllowlistAndPkTiebreaker()throws Exception {
        mvc.perform(get("/api/sales-parts?sort=currentStandard.prft_dstrb_rt,desc&page=2")).andExpect(status().isOk());
        verify(mapper).parts(argThat(q->q.order().equals("prft_dstrb_rt desc, bsn_part_id ASC")&&q.offset()==50));
    }
    @Test void detailHasLatestFirstThreePeriodsAndBoundaries()throws Exception {
        var past=new SalesPartStandard("2025-01","이전",BigDecimal.TEN,1,"1","2025-12","지난 기준");
        when(mapper.standards(any())).thenReturn(List.of(future,current,past));
        mvc.perform(get("/api/sales-parts/2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.standards.length()").value(3)).andExpect(jsonPath("$.data.standards[0].status").value("예정")).andExpect(jsonPath("$.data.standards[1].aply_end_ym").value("2026-10")).andExpect(jsonPath("$.data.standards[2].status").value("지난 기준")).andExpect(jsonPath("$.data.lastClosedMonth").value("2026-09")).andExpect(jsonPath("$.data.firstAllowedMonth").value("2026-10"));
    }
    @Test void inactiveDetailIsPublic()throws Exception {when(mapper.part(any())).thenReturn(new PartRow("2","중지","N","N",1,0,0));mvc.perform(get("/api/sales-parts/2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N"));}
    @Test void missingPartReturns404()throws Exception {when(mapper.part(any())).thenReturn(null);mvc.perform(get("/api/sales-parts/99")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));}
    @Test @DisplayName("auth business side-effect: detail preserves current standard and separates future without writes")
    void detailSeparatesFutureAndDoesNotWrite()throws Exception {mvc.perform(get("/api/sales-parts/2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentStandard.prft_dstrb_rt").value(40)).andExpect(jsonPath("$.data.scheduledChanges[0].prft_dstrb_rt").value(45));untouched();}
    @Test void noCloseReturnsNullBounds()throws Exception {when(mapper.lastClosed()).thenReturn(null);mvc.perform(get("/api/sales-parts/2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.lastClosedMonth").isEmpty()).andExpect(jsonPath("$.data.firstAllowedMonth").isEmpty());}
    @ParameterizedTest @ValueSource(strings={"0","-1","9223372036854775808","abc"})
    void invalidIdRejectedBeforeSql(String id)throws Exception {mvc.perform(get("/api/sales-parts/"+id)).andExpect(status().isBadRequest());verify(mapper,never()).part(any());}
    @Test @DisplayName("auth happy business side-effect: INSERT part and bsn_part_std_hist with data_chg_hist aft_val")
    void publicCreateUsesGeneratedHeaderThenDetailThenTwoAudits()throws Exception {
        when(mapper.standards(any())).thenReturn(List.of(future));
        mvc.perform(post("/api/sales-parts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_id").value("2")).andExpect(jsonPath("$.data.hq_yn").value("N")).andExpect(jsonPath("$.data.use_yn").value("Y")).andExpect(jsonPath("$.data.currentStandard").isEmpty()).andExpect(jsonPath("$.data.scheduledChanges[0].aply_bgn_ym").value("2026-11")).andExpect(jsonPath("$.data.scheduledChanges[0].aply_end_ym").isEmpty()).andExpect(header().exists("X-Request-Id"));
        var ordered=inOrder(mapper);ordered.verify(mapper).lockParts();ordered.verify(mapper).nameExists("신규");ordered.verify(mapper).lastClosed();ordered.verify(mapper).insertPart(argThat(w->w.actor()==null));ordered.verify(mapper).insertStandard(argThat(w->w.id()==2L&&w.actor()==null));
        verify(mapper).appendHistory(argThat(h->h.table().equals("mst.bsn_part")&&h.id().equals("2")&&h.actor()==null));
        verify(mapper).appendHistory(argThat(h->h.table().equals("mst.bsn_part_std_hist")&&h.id().equals("3")&&h.after().equals("2026-11부터 · 파트장 파트장 · 40% · 1,500,000원")));
    }
    @ParameterizedTest @CsvSource({"prft_dstrb_rt,120","prft_dstrb_rt,-1","prft_dstrb_rt,0.001","bsn_fee_amt,0","bsn_fee_amt,1.5"})
    void invalidNumbersNeverWrite(String field,String value)throws Exception {
        var b=input();b.put(field,new BigDecimal(value));
        mvc.perform(post("/api/sales-parts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty());untouched();
    }
    @ParameterizedTest @ValueSource(strings={"bsn_part_nm","ldr_nm","aply_bgn_ym","prft_dstrb_rt","bsn_fee_amt"})
    void requiredFields(String field)throws Exception {var b=input();b.remove(field);create(b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"bsn_part_nm","ldr_nm"})
    void blankText(String field)throws Exception {var b=input();b.put(field,"  ");create(b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @CsvSource({"bsn_part_nm,31","ldr_nm,51"})
    void lengthLimit(String field,int length)throws Exception {var b=input();b.put(field,"가".repeat(length));create(b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"2026-13","2026-1","0000-01"})
    void invalidMonth(String month)throws Exception {var b=input();b.put("aply_bgn_ym",month);create(b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_bgn_ym"));untouched();}
    @ParameterizedTest @ValueSource(ints={0,100})
    void percentageEndpointsAndMaximumTextLengthsAccepted(int rate)throws Exception {var b=input();b.put("prft_dstrb_rt",rate);b.put("bsn_part_nm","가".repeat(30));b.put("ldr_nm","나".repeat(50));create(b).andExpect(status().isOk());}
    @Test void duplicateNameIs409WithoutMutation()throws Exception {when(mapper.nameExists(anyString())).thenReturn(true);create(input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME")).andExpect(jsonPath("$.message").value("같은 이름의 파트가 이미 있습니다."));untouched();}
    @Test void closedMonthIs409WithoutMutation()throws Exception {var b=input();b.put("aply_bgn_ym","2026-09");create(b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")).andExpect(jsonPath("$.field").value("aply_bgn_ym"));untouched();}
    @Test void noClosedMonthAllowsExplicitPastMonth()throws Exception {when(mapper.lastClosed()).thenReturn(null);var b=input();b.put("aply_bgn_ym","2025-01");create(b).andExpect(status().isOk());}
    @Test void unknownFieldIsRejected()throws Exception {var b=input();b.put("ldr_user_id",1);create(b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ldr_user_id"));untouched();}
}
