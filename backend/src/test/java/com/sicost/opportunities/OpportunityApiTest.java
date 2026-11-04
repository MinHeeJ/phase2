package com.sicost.opportunities;

import static com.sicost.opportunities.OpportunityModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
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
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers=OpportunityController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,OpportunityService.class,CurrentUser.class,OpportunityApiTest.Time.class})
class OpportunityApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean OpportunityMapper mapper;
    Map<Long,Row> rows;
    Map<Long,List<NeedRow>> needs;
    List<History> history;
    List<Request> requests;
    @BeforeEach void setup() {
        rows=new LinkedHashMap<>();needs=new HashMap<>();history=new ArrayList<>();requests=new ArrayList<>();
        when(mapper.activeCode(any())).thenReturn(true);when(mapper.activeCustomer(anyLong())).thenReturn(true);when(mapper.activePart(anyLong())).thenReturn(true);
        when(mapper.nextId()).thenAnswer(i->100L+rows.size());
        when(mapper.criterion(any())).thenAnswer(i->switch(i.getArgument(0,Criteria.class).key()) {
            case "QUOTE_TARGET_MARGIN"->new BigDecimal("20");case "QUOTE_APPROVAL_RATE"->new BigDecimal("15");case "QUOTE_APPROVAL_TOLERANCE"->new BigDecimal("0.50");
            case "STAGE_PROB_PROP"->new BigDecimal("30");case "STAGE_PROB_NEGO"->new BigDecimal("60");case "STAGE_PROB_PRE"->new BigDecimal("90");default->new BigDecimal("10");});
        when(mapper.costs(any())).thenReturn(List.of(new CostSource(1,"BE","MID",1,"2026-09",new BigDecimal("600000")),new CostSource(2,"BE","MID",2,"2026-09",new BigDecimal("600000"))));
        when(mapper.row(anyLong())).thenAnswer(i->rows.get(i.getArgument(0,Long.class)));
        when(mapper.lock(anyLong())).thenAnswer(i->rows.get(i.getArgument(0,Long.class)));
        when(mapper.needs(anyLong())).thenAnswer(i->List.copyOf(needs.getOrDefault(i.getArgument(0,Long.class),Collections.emptyList())));
        when(mapper.approvals(anyLong())).thenAnswer(i->requests.stream().filter(r->r.id()==i.getArgument(0,Long.class)).map(r->new Approval("REQUEST",null,null,LocalDateTime.of(2026,10,23,0,0),r.margin())).toList());
        doAnswer(i->{var w=i.getArgument(0,Write.class);rows.put(w.id(),row(w));return null;}).when(mapper).insert(any());
        doAnswer(i->{var w=i.getArgument(0,Write.class);rows.put(w.id(),row(w));return null;}).when(mapper).update(any());
        doAnswer(i->{store(i.getArgument(0));return null;}).when(mapper).insertNeed(any());
        doAnswer(i->{store(i.getArgument(0));return null;}).when(mapper).updateNeed(any());
        doAnswer(i->{history.add(i.getArgument(0));return null;}).when(mapper).appendHistory(any());
        doAnswer(i->{requests.add(i.getArgument(0));return null;}).when(mapper).requestApproval(any());
        doAnswer(i->{long id=i.getArgument(0);needs.values().forEach(ns->ns.removeIf(n->n.need_id()==id));return null;}).when(mapper).retireNeed(anyLong());
        when(mapper.matching(any())).thenAnswer(i->matching(i.getArgument(0)));
        when(mapper.count(any())).thenAnswer(i->(long)matching(i.getArgument(0)).size());
        when(mapper.list(any())).thenAnswer(i->{var q=i.getArgument(0,OpportunityQuery.class);return matching(q).stream().skip(q.offset()).limit(q.size()).toList();});
    }
    List<Row> matching(OpportunityQuery q){return rows.values().stream().filter(r->r.oppt_stts_cd().equals(q.status())&&(q.q()==null||r.oppt_nm().contains(q.q()))&&(q.stage()==null||r.bsn_stg_cd().equals(q.stage()))).toList();}
    Row row(Write w) {
        var s=w.input();var q=w.quote();var prior=rows.get(w.id());
        return new Row(w.id(),"O-"+w.id(),s.oppt_nm(),s.cust_ptnr_id(),s.bsn_part_id(),s.owner_user_id(),s.bsn_stg_cd(),w.probability(),s.expc_amt(),s.cntrct_ty_cd(),s.bgn_ym(),s.mth_cnt(),s.next_action(),s.next_action_dt(),s.rmrk_desc(),s.amount_difference_reason(),"OPEN",null,null,LocalDateTime.of(2026,10,23,0,0),q.approval_state(),q.expc_sls_amt(),q.expc_cst_amt(),q.expc_mrgn_rt(),w.retainApproval()&&prior!=null?prior.aprv_mrgn_rt():null,null,null);
    }
    void store(NeedWrite w){var ns=needs.computeIfAbsent(w.id(),k->new ArrayList<>());long id=w.needId()==null?1000L+ns.size():w.needId();ns.removeIf(n->n.need_id()==id);var n=w.input();ns.add(new NeedRow(id,n.job_cd(),n.grd_cd(),n.psn_cnt(),n.bgn_ym(),n.mth_cnt(),n.mth_mm_qty(),n.expc_untprc_amt(),n.rmrk_desc(),w.cost(),w.cost(),w.target()));}
    Map<String,Object> body(){var b=new LinkedHashMap<String,Object>();b.put("oppt_nm","신규 기회");b.put("cust_ptnr_id",1);b.put("bsn_part_id",1);b.put("cntrct_ty_cd","MO");b.put("expc_amt",3000000);b.put("bgn_ym","2026-11");b.put("mth_cnt",3);b.put("needs",List.of(need()));return b;}
    Map<String,Object> need(){return new LinkedHashMap<>(Map.of("job_cd","BE","grd_cd","MID","psn_cnt",2,"bgn_ym","2026-11","mth_cnt",3,"mth_mm_qty",new BigDecimal("0.50"),"expc_untprc_amt",1000000));}
    ResultActions create(Map<String,Object> b)throws Exception{return mvc.perform(post("/api/opportunities").contentType("application/json").content(json.writeValueAsString(b)));}
    ResultActions update(Map<String,Object> b)throws Exception{return mvc.perform(put("/api/opportunities/100").contentType("application/json").content(json.writeValueAsString(b)));}
    void error(ResultActions r,String field)throws Exception{r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());}
    Map<String,Object> edit(){var b=body();var n=need();n.put("need_id",1000);b.put("needs",List.of(n));return b;}
    @Test void anonymousCreateReadbackSnapshotsAndGeneratedNumber()throws Exception{
        create(body()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.oppt_no").value("O-100"));
        mvc.perform(get("/api/opportunities/100")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_stg_cd").value("DISC")).andExpect(jsonPath("$.data.win_prblt_rt").value(10)).andExpect(jsonPath("$.data.quote.expc_sls_amt").value(3000000)).andExpect(jsonPath("$.data.quote.expc_cst_amt").value(1800000)).andExpect(jsonPath("$.data.quote.expc_mrgn_rt").value(40)).andExpect(jsonPath("$.data.needs[0].need_id").value(1000));
        assertThat(history).hasSize(1);assertThat(history.get(0).actor()).isNull();assertThat(requests).isEmpty();
        var order=inOrder(mapper);order.verify(mapper).insert(any());order.verify(mapper).insertNeed(any());order.verify(mapper).appendHistory(any());
    }
    @Test void anonymousListFiltersKpiAcrossPageAndReadsDoNotWrite()throws Exception{
        create(body());var b=body();b.put("oppt_nm","다른 기회");create(b);clearInvocations(mapper);
        mvc.perform(get("/api/opportunities").param("q","신규")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.kpi.expected_amount").value(3000000)).andExpect(jsonPath("$.data.items[0].oppt_id").value(100));
        mvc.perform(get("/api/opportunities").param("page","1").param("size","20")).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.kpi.needed_people").value(4));
        mvc.perform(get("/api/opportunities/100")).andExpect(status().isOk());verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).appendHistory(any());verify(mapper,never()).requestApproval(any());
    }
    @Test void emptyResultHasZeroTotalAndNoInventedRows()throws Exception{mvc.perform(get("/api/opportunities")).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));}
    @Test void weightedListUsesSamePerOpportunityRoundingAsPipeline()throws Exception {
        var b=body();b.put("expc_amt",1);b.put("win_prblt_rt",60);b.put("amount_difference_reason","예상금액 협의 중");
        create(b).andExpect(status().isOk());create(b).andExpect(status().isOk());
        mvc.perform(get("/api/opportunities")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.kpi.weighted_amount").value(0));
        var service=new OpportunityService(mapper,new CurrentUser(),Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")),json);
        assertThat(service.readPipeline(LocalDate.of(2026,10,23)).weightedAmount()).isZero();
    }
    @Test void inactiveCustomerCannotCreateHeaderOrHistory()throws Exception {
        when(mapper.activeCustomer(1)).thenReturn(false);
        error(create(body()),"cust_ptnr_id");verify(mapper,never()).nextId();verify(mapper,never()).insert(any());
        assertThat(history).isEmpty();assertThat(requests).isEmpty();
    }
    @Test void inactivePartCannotCreateHeaderOrHistory()throws Exception {
        when(mapper.activePart(1)).thenReturn(false);
        error(create(body()),"bsn_part_id");verify(mapper,never()).insert(any());assertThat(history).isEmpty();
    }
    @Test void inactiveJobCannotCreateDetailOrApproval()throws Exception {
        when(mapper.activeCode(new Code("JOB","BE"))).thenReturn(false);
        error(create(body()),"needs[0].job_cd");verify(mapper,never()).insertNeed(any());assertThat(requests).isEmpty();
    }
    @Test void anonymousCannotForgeOpportunityOwner()throws Exception {
        var b=body();b.put("owner_user_id",99);
        error(create(b),"owner_user_id");verify(mapper,never()).insert(any());assertThat(history).isEmpty();
    }
    @ParameterizedTest @CsvSource({"from,2026-13","to,2026-00","bsn_stg_cd,WON","oppt_stts_cd,DELETED","sort,'salary,desc'","page,-1","size,10","cust_ptnr_id,0","unknown,x"})
    void invalidListFiltersDoNotReadOrWrite(String key,String value)throws Exception{error(mvc.perform(get("/api/opportunities").param(key,value)),key);verifyNoInteractions(mapper);}
    @Test void detailRejectsUndeclaredMonthQueryAndNonPositiveId()throws Exception{error(mvc.perform(get("/api/opportunities/100").param("from","2026-13")),"from");error(mvc.perform(get("/api/opportunities/0")),"id");verifyNoInteractions(mapper);}
    @Test void absentDetailAndUpdateAre404()throws Exception{mvc.perform(get("/api/opportunities/999")).andExpect(status().isNotFound());update(body()).andExpect(status().isNotFound());verify(mapper,never()).update(any());}
    @ParameterizedTest @CsvSource({"oppt_nm,EMPTY","mth_cnt,121","bgn_ym,2026-13","win_prblt_rt,100.01","win_prblt_rt,1.001","bsn_stg_cd,WON","bsn_part_id,NULL","cust_ptnr_id,0"})
    void invalidHeader(String key,String value)throws Exception{var b=body();b.put(key,value.equals("EMPTY")?"":value.equals("NULL")?null:value.matches("[0-9.]+")?new BigDecimal(value):value);error(create(b),key);verify(mapper,never()).insert(any());assertThat(history).isEmpty();}
    @ParameterizedTest @CsvSource({"psn_cnt,0","psn_cnt,1000","mth_cnt,121","mth_mm_qty,1.01","mth_mm_qty,0","expc_untprc_amt,0"})
    void invalidNeed(String key,String value)throws Exception{var b=body();var n=need();n.put(key,new BigDecimal(value));b.put("needs",List.of(n));error(create(b),"needs[0]."+key);verify(mapper,never()).insert(any());assertThat(requests).isEmpty();}
    @Test void unknownCostHundredPercentStillRequestsApproval()throws Exception{when(mapper.costs(any())).thenReturn(List.of());create(body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.cost_status").value("UNKNOWN")).andExpect(jsonPath("$.data.quote.approval_state").value("PENDING")).andExpect(jsonPath("$.data.quote.expc_mrgn_rt").value(100));assertThat(requests).hasSize(1);}
    @Test void anonymousUpdateStageDefaultThenExplicitOverrideAndNeedIdentity()throws Exception{create(body());var b=edit();b.put("bsn_stg_cd","PROP");update(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.win_prblt_rt").value(30));b.put("win_prblt_rt",55);update(b).andExpect(status().isOk());mvc.perform(get("/api/opportunities/100")).andExpect(jsonPath("$.data.win_prblt_rt").value(55)).andExpect(jsonPath("$.data.needs[0].need_id").value(1000));mvc.perform(get("/api/opportunities")).andExpect(jsonPath("$.data.items[0].win_prblt_rt").value(55));verify(mapper,times(2)).lock(100);}
    @Test void differenceRequiresReasonAndInvalidUpdateLeavesRowsHistoryUnchanged()throws Exception{create(body());var original=rows.get(100L);var b=edit();b.put("expc_amt",1);error(update(b),"amount_difference_reason");assertThat(rows.get(100L)).isEqualTo(original);assertThat(history).hasSize(1);verify(mapper,never()).update(any());}
    @Test void zeroDenominatorDisplaysNullDifferenceRate()throws Exception{var b=body();b.put("expc_amt",0);b.put("amount_difference_reason","협의 중");create(b).andExpect(jsonPath("$.data.quote.amount_difference_rate").isEmpty()).andExpect(jsonPath("$.data.quote.amount_difference").value(3000000));}
    @Test void outsidePeriodIsWarningNotRewritten()throws Exception{var b=body();b.put("bgn_ym","2026-12");create(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.needs[0].bgn_ym").value("2026-11")).andExpect(jsonPath("$.data.needs[0].warnings[0]").isNotEmpty());}
    @Test void removedNeedReleasesAssignmentThenRetainsInactiveIdentity()throws Exception{create(body());var b=body();b.put("needs",List.of());b.put("expc_amt",0);update(b).andExpect(status().isOk());var order=inOrder(mapper);order.verify(mapper).lock(100);order.verify(mapper).releaseNeed(1000);order.verify(mapper).retireNeed(1000);}
    @Test void foreignNeedIsRejected()throws Exception{create(body());var b=edit();var n=need();n.put("need_id",9999);b.put("needs",List.of(n));error(update(b),"needs.need_id");verify(mapper,never()).update(any());}
    @ParameterizedTest @ValueSource(strings={"WON","LOST","DELETED"}) void closedIs409WithoutMutation(String status)throws Exception{create(body());var r=rows.get(100L);rows.put(100L,new Row(r.oppt_id(),r.oppt_no(),r.oppt_nm(),r.cust_ptnr_id(),r.bsn_part_id(),r.owner_user_id(),r.bsn_stg_cd(),r.win_prblt_rt(),r.expc_amt(),r.cntrct_ty_cd(),r.bgn_ym(),r.mth_cnt(),r.next_action(),r.next_action_dt(),r.rmrk_desc(),r.amount_difference_reason(),status,null,null,r.created_at(),r.approval_state(),r.expc_sls_amt(),r.expc_cst_amt(),r.expc_mrgn_rt(),null,null,null));var before=rows.get(100L);update(edit()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPPORTUNITY_CLOSED"));assertThat(rows.get(100L)).isSameAs(before);assertThat(history).hasSize(1);}
    @Test void unapprovedQuoteCannotEnterNegotiation()throws Exception{when(mapper.costs(any())).thenReturn(List.of());create(body());var b=edit();b.put("bsn_stg_cd","NEGO");update(b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_APPROVAL_REQUIRED"));verify(mapper,never()).update(any());}
    @Test void knownLowMarginRequestsApprovalIndependentlyOfMissingCost()throws Exception{when(mapper.costs(any())).thenReturn(List.of(new CostSource(1,"BE","MID",1,"2026-09",new BigDecimal("900000")),new CostSource(2,"BE","MID",2,"2026-09",new BigDecimal("900000"))));create(body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.cost_status").value("VERIFIED")).andExpect(jsonPath("$.data.quote.approval_state").value("PENDING")).andExpect(jsonPath("$.data.quote.expc_mrgn_rt").value(10));assertThat(requests).hasSize(1);}
    @Test void approvedQuoteRetainsAtHalfPointButChangedNeedsRequestsAgain()throws Exception{create(body());var tree=json.valueToTree(rows.get(100L));((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("approval_state","APPROVED").put("aprv_mrgn_rt",40.50);rows.put(100L,json.treeToValue(tree,Row.class));update(edit()).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.approval_state").value("APPROVED"));assertThat(requests).isEmpty();var b=edit();var n=need();n.put("need_id",1000);n.put("psn_cnt",3);n.put("mth_cnt",2);b.put("needs",List.of(n));update(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.approval_state").value("PENDING"));assertThat(requests).hasSize(1);}
}
