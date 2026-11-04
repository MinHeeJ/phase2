package com.sicost.candidates;

import static com.sicost.candidates.CandidateModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
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

@WebMvcTest(controllers=CandidateController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,CandidateService.class,CurrentUser.class,CandidateApiTest.Time.class})
class CandidateApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean CandidateMapper mapper;
    Map<Long,CandidateRow> rows;
    Map<Long,List<String>> skills;
    Map<Long,List<CandidateContact>> contacts;
    List<CandidateHistory> histories;
    Map<String,Object> body() {
        var b=new LinkedHashMap<String,Object>();
        b.put("cand_nm","같은 이름");b.put("cand_se_cd","FL");b.put("job_cd","BE");
        b.put("hope_untprc_amt",0);b.put("avail_dt","2026-11-30");b.put("skill",List.of("JAVA"));return b;
    }
    CandidateRow row(long id,String no,CandidateWrite s,CandidateRow old) {
        return new CandidateRow(id,no,s.cand_nm(),s.cand_se_cd(),s.vndr_ptnr_id(),s.job_cd(),s.grd_cd(),
            s.hope_untprc_amt(),s.avail_dt(),s.cntct_stts_cd(),s.tel_no(),s.source(),s.rmrk_desc(),"Y",
            old==null?null:old.dev_id(),old==null?null:old.last_cntct_dt(),old==null?LocalDateTime.of(2026,9,22,0,0):old.created_at());
    }
    @BeforeEach void setup() {
        rows=new LinkedHashMap<>();skills=new HashMap<>();contacts=new HashMap<>();histories=new ArrayList<>();
        when(mapper.activeCode(any())).thenReturn(true);when(mapper.activeVendor(anyLong())).thenReturn(true);
        when(mapper.nextId()).thenAnswer(i -> rows.size()+1L);
        when(mapper.insert(any())).thenAnswer(i -> {var w=i.getArgument(0,CandidateInsert.class);rows.put(w.id(),row(w.id(),w.no(),w.input(),null));return w.id();});
        when(mapper.row(anyLong())).thenAnswer(i -> rows.get(i.getArgument(0,Long.class)));
        when(mapper.lock(anyLong())).thenAnswer(i -> rows.containsKey(i.getArgument(0,Long.class))?i.getArgument(0):null);
        when(mapper.skills(anyLong())).thenAnswer(i -> skills.getOrDefault(i.getArgument(0,Long.class),List.of()));
        when(mapper.contacts(anyLong())).thenAnswer(i -> contacts.getOrDefault(i.getArgument(0,Long.class),List.of()));
        when(mapper.slots(anyLong())).thenReturn(List.of());
        doAnswer(i -> {var c=i.getArgument(0,CandidateChild.class);skills.computeIfAbsent(c.id(),k -> new ArrayList<>()).add(c.code());return null;}).when(mapper).insertSkill(any());
        doAnswer(i -> {skills.remove(i.getArgument(0,Long.class));return null;}).when(mapper).deleteSkills(anyLong());
        doAnswer(i -> {histories.add(i.getArgument(0));return null;}).when(mapper).appendHistory(any());
        doAnswer(i -> {
            var w=i.getArgument(0,CandidateUpdate.class);var old=rows.get(w.id());
            var tree=json.valueToTree(old.input(skills.getOrDefault(w.id(),List.of())));
            var aliases=Map.of("asgn_psbl_dt","avail_dt","cand_src_cd","source","memo_desc","rmrk_desc");
            w.changes().forEach((k,v) -> ((com.fasterxml.jackson.databind.node.ObjectNode)tree).set(aliases.getOrDefault(k,k),json.valueToTree(v)));
            rows.put(w.id(),row(w.id(),old.cand_no(),json.treeToValue(tree,CandidateWrite.class),old));return null;
        }).when(mapper).update(any());
        when(mapper.list(any())).thenAnswer(i -> rows.values().stream().toList());
        when(mapper.kpi(any())).thenAnswer(i -> new CandidateKpi(rows.size(),0,rows.size(),rows.size()));
    }
    ResultActions create(Map<String,Object> b) throws Exception {return mvc.perform(post("/api/candidates").contentType("application/json").content(json.writeValueAsString(b)));}
    ResultActions update(Map<String,Object> b) throws Exception {return mvc.perform(put("/api/candidates/1").contentType("application/json").content(json.writeValueAsString(b)));}
    void error(ResultActions r,String field) throws Exception {r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(jsonPath("$.success").value(false));}
    void untouched() {verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).insertSkill(any());verify(mapper,never()).appendHistory(any());}
    @Test void anonymousListReturnsCandidatePageAndEmptyKpiWithoutWrites() throws Exception {
        mvc.perform(get("/api/candidates")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.kpi.candidate_count").value(0));untouched();
    }
    @Test void filteredListUsesSameQueryForRowsAndWholeFilteredKpi() throws Exception {
        mvc.perform(get("/api/candidates").param("job","BE").param("grade","SENIOR").param("skill","JAVA").param("shortage_month","2026-12")
            .param("page","2").param("size","20").param("sort","cand_nm,desc")).andExpect(status().isOk());
        var capture=org.mockito.ArgumentCaptor.forClass(CandidateQuery.class);verify(mapper).list(capture.capture());
        var q=capture.getValue();verify(mapper).kpi(q);assertThat(q.offset()).isEqualTo(40);assertThat(q.shortageEnd()).isEqualTo(LocalDate.of(2026,12,31));
        assertThat(q.cutoff()).isEqualTo(LocalDate.of(2026,9,23));assertThat(q.nextMonthEnd()).isEqualTo(LocalDate.of(2026,11,30));untouched();
    }
    @ParameterizedTest @CsvSource({"shortage_month,2026-13","avail_dt,2026-02-30","cand_se_cd,FT","cntct_stts_cd,OTHER","uncontacted_only,yes","sort,tel_no.asc","page,-1","size,10","vndr_ptnr_id,0","unknown,x"})
    void invalidListFiltersAreFieldErrorsAndNeverWrite(String key,String value) throws Exception {error(mvc.perform(get("/api/candidates").param(key,value)),key);untouched();}
    @Test void anonymousCreateReturns200AndDetailReadbackWithGeneratedNumberAndSkills() throws Exception {
        var b=body();b.put("vndr_ptnr_id",10);create(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.cand_no").value("CAND-0001"));
        mvc.perform(get("/api/candidates/1")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.cand_nm").value("같은 이름"))
            .andExpect(jsonPath("$.data.vndr_ptnr_id").isEmpty()).andExpect(jsonPath("$.data.skill[0]").value("JAVA"))
            .andExpect(jsonPath("$.data.contacts").isArray()).andExpect(jsonPath("$.data.matching_slots").isArray()).andExpect(jsonPath("$.data.dev_id").isEmpty());
        var order=inOrder(mapper);order.verify(mapper).nextId();order.verify(mapper).insert(any());order.verify(mapper).insertSkill(any());
        assertThat(histories).allMatch(h -> h.actor()==null && h.type().equals("INSERT"));
    }
    @Test void duplicateNamesNeverLinkToDeveloperAndAllocateDifferentIds() throws Exception {create(body());create(body());assertThat(rows.keySet()).containsExactly(1L,2L);assertThat(rows.values()).extracting(CandidateRow::cand_no).containsExactly("CAND-0001","CAND-0002");assertThat(rows.values()).allMatch(r -> r.dev_id()==null);}
    @Test void insertReturnedKeyOwnsChildrenAuditAndReadbackWithoutListLookup() throws Exception {
        doAnswer(i -> {
            var w=i.getArgument(0,CandidateInsert.class);
            rows.put(41L,row(41,w.no(),w.input(),null));return 41L;
        }).when(mapper).insert(any());
        create(body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.cand_id").value(41))
            .andExpect(jsonPath("$.data.skill[0]").value("JAVA"));
        var order=inOrder(mapper);
        order.verify(mapper).insert(any());
        order.verify(mapper).insertSkill(new CandidateChild(41,"JAVA"));
        order.verify(mapper,atLeastOnce()).appendHistory(any());
        order.verify(mapper).row(41);
        verify(mapper,never()).row(1);verify(mapper,never()).list(any());
        assertThat(histories).allMatch(h -> h.id().equals("41"));
    }
    @Test void missingOsVendorRejectedBeforeInsert() throws Exception {var b=body();b.put("cand_se_cd","OS");error(create(b),"vndr_ptnr_id");untouched();}
    @Test void inactiveOrCustomerOnlyVendorRejectedBeforeInsert() throws Exception {when(mapper.activeVendor(10)).thenReturn(false);var b=body();b.put("cand_se_cd","OS");b.put("vndr_ptnr_id",10);error(create(b),"vndr_ptnr_id");untouched();}
    @Test void negativePriceRejectedBeforeInsert() throws Exception {var b=body();b.put("hope_untprc_amt",-1);error(create(b),"hope_untprc_amt");untouched();}
    @ParameterizedTest @ValueSource(strings={"cand_nm","job_cd","avail_dt","hope_untprc_amt"})
    void missingRequiredCreateFieldIs400(String field) throws Exception {var b=body();b.remove(field);error(create(b),field);untouched();}
    @Test void badCodeIs400() throws Exception {when(mapper.activeCode(new CandidateCode("SKILL","JAVA"))).thenReturn(false);error(create(body()),"skill");untouched();}
    @Test void duplicateSkillsRejected() throws Exception {var b=body();b.put("skill",List.of("JAVA","JAVA"));error(create(b),"skill");untouched();}
    @Test void unknownFieldRejected() throws Exception {var b=body();b.put("dev_id",1);error(create(b),"dev_id");untouched();}
    @Test void trailingJsonRejected() throws Exception {mvc.perform(post("/api/candidates").contentType("application/json").content("{} false")).andExpect(status().isBadRequest());untouched();}
    @Test void anonymousDetailReadHasNoWrites() throws Exception {create(body());clearInvocations(mapper);mvc.perform(get("/api/candidates/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cand_id").value(1));untouched();}
    @Test void detailRejectsUndeclaredMonthInsteadOfSilentlyIgnoringIt() throws Exception {error(mvc.perform(get("/api/candidates/1").param("shortage_month","2026-13")),"shortage_month");untouched();}
    @Test void missingDetailAndUpdateAre404() throws Exception {mvc.perform(get("/api/candidates/9")).andExpect(status().isNotFound());update(body()).andExpect(status().isNotFound());untouched();}
    @ParameterizedTest @ValueSource(strings={"0","-1","x","9223372036854775808"})
    void invalidDetailIdsAre400(String id) throws Exception {error(mvc.perform(get("/api/candidates/"+id)),"id");untouched();}
    @Test void anonymousUpdatePreservesIdentityNullsFlVendorAndAuditsEachChangedField() throws Exception {
        var b=body();b.put("cand_se_cd","OS");b.put("vndr_ptnr_id",10);create(b);histories.clear();
        b=body();b.put("cand_nm","수정");b.put("hope_untprc_amt",100);b.put("skill",List.of());update(b).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/candidates/1")).andExpect(jsonPath("$.data.cand_no").value("CAND-0001")).andExpect(jsonPath("$.data.cand_nm").value("수정")).andExpect(jsonPath("$.data.vndr_ptnr_id").isEmpty()).andExpect(jsonPath("$.data.skill").isEmpty());
        assertThat(histories).anyMatch(h -> h.column().equals("hope_untprc_amt") && h.before().equals("0") && h.after().equals("100"));
        assertThat(histories).anyMatch(h -> h.column().equals("skill") && h.before().equals("JAVA") && h.after().isEmpty());verify(mapper).lock(1);
    }
    @ParameterizedTest @ValueSource(strings={"cand_nm","job_cd","avail_dt"})
    void missingUpdateFieldLeavesOriginalAndHistory(String field) throws Exception {create(body());var original=rows.get(1L);histories.clear();var b=body();b.remove(field);error(update(b),field);assertThat(rows.get(1L)).isEqualTo(original);assertThat(histories).isEmpty();verify(mapper,never()).update(any());}
    @Test void negativeUpdatePriceLeavesOriginalAndHistory() throws Exception {create(body());histories.clear();var b=body();b.put("hope_untprc_amt",-1);error(update(b),"hope_untprc_amt");assertThat(rows.get(1L).hope_untprc_amt()).isZero();assertThat(histories).isEmpty();}
    @Test void invalidUpdateCodePreservesOriginalSkillsAndHistory() throws Exception {
        create(body());var original=rows.get(1L);histories.clear();clearInvocations(mapper);
        when(mapper.activeCode(new CandidateCode("SKILL","JAVA"))).thenReturn(false);
        error(update(body()),"skill");
        assertThat(rows.get(1L)).isEqualTo(original);assertThat(skills.get(1L)).containsExactly("JAVA");
        assertThat(histories).isEmpty();untouched();verify(mapper,never()).deleteSkills(anyLong());
    }
    @Test void invalidUpdateVendorPreservesOriginalAndHistory() throws Exception {
        create(body());var original=rows.get(1L);histories.clear();clearInvocations(mapper);
        when(mapper.activeVendor(10)).thenReturn(false);
        var b=body();b.put("cand_se_cd","OS");b.put("vndr_ptnr_id",10);
        error(update(b),"vndr_ptnr_id");assertThat(rows.get(1L)).isEqualTo(original);
        assertThat(histories).isEmpty();untouched();
    }
    @Test void unchangedUpdateDoesNotReplaceSkillsOrAppendHistory() throws Exception {
        create(body());histories.clear();clearInvocations(mapper);
        update(body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.cand_no").value("CAND-0001"));
        untouched();verify(mapper,never()).deleteSkills(anyLong());assertThat(histories).isEmpty();
    }
    @Test void latestContactStatusCannotBeOverwrittenByGenericPut() throws Exception {
        create(body());var old=rows.get(1L);var s=old.input(List.of("JAVA"));
        rows.put(1L,new CandidateRow(1,old.cand_no(),old.cand_nm(),old.cand_se_cd(),null,old.job_cd(),null,0L,old.avail_dt(),"INTV",null,null,null,"Y",null,LocalDate.of(2026,10,20),old.created_at()));
        contacts.put(1L,List.of(new CandidateContact(LocalDate.of(2026,10,20),"INTV","면접",null,LocalDateTime.of(2026,10,21,0,0))));
        var b=body();b.put("cntct_stts_cd","NONE");b.put("cand_nm","수정");update(b).andExpect(jsonPath("$.data.cntct_stts_cd").value("INTV")).andExpect(jsonPath("$.data.last_cntct_dt").value("2026-10-20"));
        verify(mapper).update(argThat(w -> !w.changes().containsKey("cntct_stts_cd")));assertThat(contacts.get(1L)).hasSize(1);
    }
    @Test void convertedSignedCannotBeUndoneWithoutContacts() throws Exception {
        create(body());var r=rows.get(1L);rows.put(1L,new CandidateRow(1,r.cand_no(),r.cand_nm(),"FL",null,r.job_cd(),null,0L,r.avail_dt(),"SIGNED",null,null,null,"Y",3L,null,r.created_at()));
        var b=body();b.put("cand_nm","수정");update(b).andExpect(jsonPath("$.data.cntct_stts_cd").value("SIGNED")).andExpect(jsonPath("$.data.dev_id").value(3));
    }
    @Test void phoneIsVisibleButNeverLeaksIntoAuditOrDiagnosticToString() throws Exception {var b=body();b.put("tel_no","010-1234-5678");create(b).andExpect(jsonPath("$.data.tel_no").value("010-1234-5678"));assertThat(histories).filteredOn(h -> h.column().equals("tel_no")).singleElement().satisfies(h -> assertThat(h.after()).isEqualTo("***"));assertThat(rows.get(1L).toString()).doesNotContain("010-1234-5678");assertThat(rows.get(1L).input(List.of()).toString()).doesNotContain("010-1234-5678");}
    @Test void matchingMarginUsesHalfUpTwoDecimalsAndUnknownExpenseWarning() {
        var match=CandidateService.matching(new CandidateSlotSource(1,2,"기회","BE","2026-11",3L,null),1L);
        assertThat(match.margin_rate()).isEqualByComparingTo("66.67");assertThat(match.team_expense()).isZero();assertThat(match.estimated()).isTrue();assertThat(match.warnings()).isNotEmpty();
    }
    @Test void zeroMonthlyPriceProducesNullMargin() {assertThat(CandidateService.matching(new CandidateSlotSource(1,2,"기회","BE","2026-11",0L,50000L),1L).margin_rate()).isNull();}
}
