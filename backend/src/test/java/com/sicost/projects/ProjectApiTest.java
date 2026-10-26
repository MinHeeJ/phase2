package com.sicost.projects;

import static com.sicost.projects.ProjectModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.validation.ProjectPhases;
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

@WebMvcTest(controllers = ProjectController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,ProjectService.class,
    CurrentUser.class,PermissionService.class,ProjectApiTest.Time.class})
class ProjectApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectMapper mapper;
    Map<Long,ProjectRow> rows;
    Map<Long,List<String>> skills;
    List<ProjectHistory> history;
    List<ProjectPhase> phases;
    List<ProjectPart> parts;
    Map<String,Object> body() {
        Map<String,Object> b = new LinkedHashMap<>();
        b.put("prj_nm","프로젝트"); b.put("cntrct_ty_cd","MO"); b.put("prj_stts_cd","RUN");
        b.put("cust_ptnr_id",1); b.put("bsn_part_id",1); b.put("init_cntrct_amt",500000000L);
        b.put("cntrct_bgn_dt","2026-03-01"); b.put("cntrct_end_dt","2026-12-31");
        b.put("prj_skl",List.of("JAVA")); return b;
    }
    ProjectRow row(long id, String no, ProjectSave s, Long current, LocalDate end,
            BigDecimal mm, String use) {
        return new ProjectRow(id,no,s.prj_nm(),s.cust_ptnr_id(),s.cntrct_ty_cd(),s.prj_stts_cd(),
            s.win_prblt_rt(),s.cntrct_dt(),s.cntrct_bgn_dt(),s.cntrct_end_dt(),s.prfm_bgn_dt(),
            s.bsn_part_id(),s.stlm_mthd_cd(),s.pm_dev_id(),s.free_sprt_yn(),s.quot_amt(),s.init_cntrct_amt(),
            s.trgt_mrgn_rt(),s.trgt_cst_amt(),s.indty_cd(),s.duty_fld_nm(),s.prev_prj_id(),s.rmrk_desc(),
            current,end,mm,use,s.cust_ptnr_id() == null ? null : "고객사",
            s.bsn_part_id() == null ? null : "본사(직접 영업)",null,0);
    }
    @BeforeEach void setup() throws Exception {
        rows = new LinkedHashMap<>(); skills = new HashMap<>(); history = new ArrayList<>();
        phases = new ArrayList<>(); parts = new ArrayList<>();
        when(mapper.activeCode(any())).thenReturn(true);
        when(mapper.activeCustomer(anyLong())).thenReturn(true);
        when(mapper.activePart(anyLong())).thenReturn(true);
        when(mapper.activeDeveloper(anyLong())).thenReturn(true);
        when(mapper.activeProject(anyLong())).thenReturn(true);
        when(mapper.latestPart(anyLong())).thenReturn(null);
        when(mapper.budgetRate(any())).thenReturn(new BigDecimal("80"));
        when(mapper.phases(any())).thenAnswer(i -> {
            int n = i.getArgument(0).equals("PHASE_TK") ? 7 : 5;
            List<ProjectPhases.Definition> defs = new ArrayList<>();
            for (int k = 1; k <= n; k++) defs.add(new ProjectPhases.Definition(k,"단계"+k,
                BigDecimal.valueOf(k == n ? 100 - (n-1)*10 : 10)));
            return defs;
        });
        when(mapper.maximumSuffix(any())).thenAnswer(i -> rows.size());
        when(mapper.insert(any())).thenAnswer(i -> {
            var w = i.getArgument(0,ProjectWrite.class); long id = rows.size()+1L;
            rows.put(id,row(id,w.no(),w.input(),w.input().init_cntrct_amt(),w.input().cntrct_end_dt(),
                w.input().cntrct_ty_cd().equals("IN") ? null : new BigDecimal("0.00"),"Y")); return id;
        });
        when(mapper.row(any())).thenAnswer(i -> rows.get(i.getArgument(0,ProjectRead.class).id()));
        when(mapper.skills(anyLong())).thenAnswer(i -> skills.getOrDefault(i.getArgument(0,Long.class),Collections.emptyList()));
        doAnswer(i -> { var c = i.getArgument(0,ProjectChild.class); skills.computeIfAbsent(c.id(),k -> new ArrayList<>()).add(c.code()); return null; }).when(mapper).insertSkill(any());
        doAnswer(i -> { skills.remove(i.getArgument(0,Long.class)); return null; }).when(mapper).deleteSkills(anyLong());
        doAnswer(i -> { history.add(i.getArgument(0)); return null; }).when(mapper).appendHistory(any());
        doAnswer(i -> { phases.add(i.getArgument(0)); return null; }).when(mapper).insertPhase(any());
        doAnswer(i -> { parts.add(i.getArgument(0)); return null; }).when(mapper).insertPart(any());
        doAnswer(i -> {
            var w = i.getArgument(0,ProjectUpdate.class); var old = rows.get(w.id());
            var tree = json.valueToTree(old.input(skills.getOrDefault(w.id(),Collections.emptyList())));
            w.changes().forEach((k,v) -> { if (tree.has(k)) ((com.fasterxml.jackson.databind.node.ObjectNode)tree).set(k,json.valueToTree(v)); });
            var s = json.treeToValue(tree,ProjectSave.class);
            rows.put(w.id(),row(w.id(),old.prj_no(),s,
                w.changes().containsKey("cur_cntrct_amt") ? (Long)w.changes().get("cur_cntrct_amt") : old.cur_cntrct_amt(),
                (LocalDate)w.changes().getOrDefault("cur_end_dt",old.cur_end_dt()),
                (BigDecimal)w.changes().getOrDefault("cntrct_mm_qty",old.cntrct_mm_qty()),old.use_yn()));
            return null;
        }).when(mapper).update(any());
        when(mapper.list(any())).thenAnswer(i -> rows.values().stream().map(r -> new ProjectListItem(r.prj_id(),r.prj_no(),r.prj_nm(),r.custName(),r.cntrct_ty_cd(),r.cntrct_bgn_dt(),r.cur_end_dt(),r.cur_cntrct_amt(),r.assignmentCount(),r.bsn_part_id(),r.partName(),r.prj_stts_cd())).toList());
        when(mapper.count(any())).thenAnswer(i -> (long)rows.size());
    }
    ResultActions create(Map<String,Object> b) throws Exception { return mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b))); }
    ResultActions update(Map<String,Object> b) throws Exception { return mvc.perform(put("/api/projects/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b))); }
    void untouched() { verify(mapper,never()).insert(any()); verify(mapper,never()).appendHistory(any()); verify(mapper,never()).insertPhase(any()); verify(mapper,never()).insertPart(any()); verify(mapper,never()).insertSkill(any()); }
    void error(ResultActions r, String field) throws Exception { r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.requestId").isNotEmpty()); }
    @Test void anonymousListReturnsExactPageAndSeoulDefaultYear() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.unassignedCount").value(0));
        verify(mapper).list(argThat(q -> q.yearStart().equals(LocalDate.of(2026,1,1)) && q.today().equals(LocalDate.of(2026,10,23)) && q.order().equals("\"cntrct_bgn_dt\" desc NULLS LAST, prj_id ASC"))); untouched();
    }
    @Test void listPropagatesAndFiltersAndIndependentNoticeCount() throws Exception {
        when(mapper.unassignedCount(any())).thenReturn(2L);
        mvc.perform(get("/api/projects").param("year","2025").param("type","MO").param("status","END").param("part","NONE").param("q","고객").param("page","2").param("size","20").param("sort","custName,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unassignedCount").value(2));
        verify(mapper).list(argThat(q -> q.yearEnd().equals(LocalDate.of(2025,12,31)) && q.type().equals("MO") && q.status().equals("END") && q.part().equals("NONE") && q.q().equals("고객") && q.offset()==40)); untouched();
    }
    @ParameterizedTest @CsvSource({"year,0","year,no","page,-1","size,10","sort,invalid","part,0","part,9223372036854775808","type,X","status,X","unknown,x"})
    void invalidFilters(String key,String value) throws Exception { error(mvc.perform(get("/api/projects").param(key,value)),key); untouched(); }
    @Test void anonymousCreateMoReadbackAndAtomicChildren() throws Exception {
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.prj_no").value("P-2601"));
        mvc.perform(get("/api/projects/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cur_cntrct_amt").value(500000000)).andExpect(jsonPath("$.data.trgt_cst_amt").value(400000000)).andExpect(jsonPath("$.data.prj_skl[0]").value("JAVA")).andExpect(jsonPath("$.data.pm_user_id").doesNotExist());
        assertThat(parts).containsExactly(new ProjectPart(1,"2026-03",1L,null)); assertThat(phases).hasSize(5);
        assertThat(history).singleElement().satisfies(h -> { assertThat(h.id()).isEqualTo("1"); assertThat(h.type()).isEqualTo("INSERT"); assertThat(h.actor()).isNull(); });
        var order = inOrder(mapper); order.verify(mapper).numberLock(); order.verify(mapper).maximumSuffix("P-26"); order.verify(mapper).insert(any()); order.verify(mapper).insertSkill(any()); order.verify(mapper).insertPart(any());
    }
    @Test void internalAnonymousCreationNullsCustomerPartAndMoney() throws Exception {
        var b = body(); b.put("cntrct_ty_cd","IN"); b.put("trgt_cst_amt",10); b.put("quot_amt",10);
        create(b).andExpect(status().isOk());
        mvc.perform(get("/api/projects/1")).andExpect(jsonPath("$.data.cust_ptnr_id").isEmpty()).andExpect(jsonPath("$.data.bsn_part_id").isEmpty()).andExpect(jsonPath("$.data.init_cntrct_amt").isEmpty()).andExpect(jsonPath("$.data.cur_cntrct_amt").isEmpty()).andExpect(jsonPath("$.data.trgt_cst_amt").isEmpty()).andExpect(jsonPath("$.data.stlm_mthd_cd").isEmpty());
        assertThat(phases).hasSize(7); assertThat(parts.get(0).part()).isNull();
    }
    @Test void nameOneHundredAndSingleDayAreValid() throws Exception { var b=body(); b.put("prj_nm","가".repeat(100)); b.put("cntrct_end_dt","2026-03-01"); create(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.prj_nm").value("가".repeat(100))); assertThat(phases).hasSize(5); assertThat(phases.get(0).plan().start()).isNull(); assertThat(phases.get(4).plan().end()).isEqualTo(LocalDate.of(2026,3,1)); }
    @Test void nameTrimAndDuplicateNamesGetDistinctNumbers() throws Exception { var b=body(); b.put("prj_nm","  같은 이름  "); create(b).andExpect(status().isOk()); create(b).andExpect(status().isOk()); mvc.perform(get("/api/projects")).andExpect(jsonPath("$.data.items[0].prj_nm").value("같은 이름")).andExpect(jsonPath("$.data.items[0].prj_no").value("P-2601")).andExpect(jsonPath("$.data.items[1].prj_no").value("P-2602")); }
    @Test void integerTargetFloorIsExact() throws Exception { var b=body(); b.put("init_cntrct_amt",123456789); create(b).andExpect(jsonPath("$.data.trgt_cst_amt").value(98765431)); }
    @ParameterizedTest @CsvSource({"prj_nm,LONG","cntrct_end_dt,2026-02-28","prfm_bgn_dt,2026-02-28","prfm_bgn_dt,2027-01-01","bsn_part_id,NULL","cust_ptnr_id,NULL","init_cntrct_amt,0","init_cntrct_amt,9223372036854775808","trgt_mrgn_rt,101","trgt_mrgn_rt,-1","trgt_mrgn_rt,1.001","prj_stts_cd,HOLD","duty_fld_nm,LONG","rmrk_desc,LONG"})
    void createValidationBeforeAnyWrite(String key,String value) throws Exception {
        var b=body(); Object v=value.equals("LONG") ? "가".repeat(key.equals("rmrk_desc")?501:101) : value.equals("NULL")?null: value.matches("-?[0-9.]+")?new BigDecimal(value):value;
        b.put(key,v); error(create(b),key); untouched();
    }
    @Test void inactiveCustomerIsRejected() throws Exception { when(mapper.activeCustomer(1)).thenReturn(false); error(create(body()),"cust_ptnr_id"); untouched(); }
    @Test void inactivePartIsRejected() throws Exception { when(mapper.activePart(1)).thenReturn(false); error(create(body()),"bsn_part_id"); untouched(); }
    @Test void inactiveCodeIsRejected() throws Exception { when(mapper.activeCode(new ProjectCode("SKILL","JAVA"))).thenReturn(false); error(create(body()),"prj_skl"); untouched(); }
    @Test void malformedJsonAndUnknownFieldDoNotWrite() throws Exception { mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{} trailing")).andExpect(status().isBadRequest()); var b=body(); b.put("salary",42); error(create(b),"salary"); untouched(); }
    @Test void missingProjectIs404() throws Exception { mvc.perform(get("/api/projects/9")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")); untouched(); }
    @ParameterizedTest @ValueSource(strings={"0","P-123","P-2601x","9223372036854775808","-1"}) void badIdentifiers(String id) throws Exception { error(mvc.perform(get("/api/projects/"+id)),"id"); untouched(); }
    @Test void canonicalDeepLinkUsesUniqueNumberEvenForDisabled() throws Exception { create(body()); var old=rows.get(1L); rows.put(1L,row(1,old.prj_no(),old.input(skills.get(1L)),old.cur_cntrct_amt(),old.cur_end_dt(),old.cntrct_mm_qty(),"N")); when(mapper.resolveNumber("P-2601")).thenReturn(1L); mvc.perform(get("/api/projects/P-2601")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N")); }
    @Test void anonymousNameUpdateAuditsOneColumnAndNoopAddsNothing() throws Exception {
        create(body()); history.clear(); var b=body(); b.put("prj_nm","바뀐 이름");
        mvc.perform(put("/api/projects/1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(b))).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.prj_nm").value("바뀐 이름"));
        assertThat(history).containsExactly(new ProjectHistory("1","UPDATE","prj_nm","프로젝트","바뀐 이름",null));
        update(b).andExpect(status().isOk()); assertThat(history).hasSize(1); verify(mapper,times(1)).update(any());
    }
    @Test void manualCostAnonymousPersistsAndReadonlyFieldsIgnored() throws Exception {
        create(body()); var b=body(); b.put("trgt_cst_amt",123L); b.put("prj_no","P-9999"); b.put("cur_cntrct_amt",1); b.put("cur_end_dt","2099-01-01"); b.put("cntrct_mm_qty",99); b.put("bsn_part_id",2);
        update(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.trgt_cst_amt").value(123)).andExpect(jsonPath("$.data.prj_no").value("P-2601")).andExpect(jsonPath("$.data.cur_cntrct_amt").value(500000000)).andExpect(jsonPath("$.data.bsn_part_id").value(1)).andExpect(jsonPath("$.data.cur_end_dt").value("2026-12-31")).andExpect(jsonPath("$.data.cntrct_mm_qty").value(0));
    }
    @Test void amountAndEndSynchronizeOnlyWithoutConfirmedChanges() throws Exception { create(body()); var b=body(); b.put("init_cntrct_amt",600000000); b.put("cntrct_end_dt","2027-01-01"); update(b).andExpect(jsonPath("$.data.cur_cntrct_amt").value(600000000)).andExpect(jsonPath("$.data.cur_end_dt").value("2027-01-01")); assertThat(phases).hasSize(5); }
    @ParameterizedTest @ValueSource(strings={"init_cntrct_amt","cntrct_end_dt"}) void confirmedInitialFieldsAre400AndUnchanged(String field) throws Exception { create(body()); history.clear(); var original=rows.get(1L); when(mapper.confirmed(1)).thenReturn(true); var b=body(); b.put(field,field.equals("init_cntrct_amt")?600000000:"2027-01-01"); error(update(b),field); assertThat(rows.get(1L)).isEqualTo(original); assertThat(history).isEmpty(); }
    @Test void reversedUpdatePreservesStoredValuesAndHistory() throws Exception { create(body()); history.clear(); var original=rows.get(1L); var b=body(); b.put("cntrct_end_dt","2026-01-01"); error(update(b),"cntrct_end_dt"); assertThat(rows.get(1L)).isEqualTo(original); assertThat(history).isEmpty(); }
    @Test void typeLockConflictDoesNotChangeAnything() throws Exception { create(body()); history.clear(); when(mapper.typeLocked(1)).thenReturn(true); var b=body(); b.put("cntrct_ty_cd","TK"); update(b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TYPE_LOCKED")); assertThat(rows.get(1L).cntrct_ty_cd()).isEqualTo("MO"); assertThat(history).isEmpty(); }
    @Test void externalInternalTransitionPreservesPhasesAndRestoresActivePart() throws Exception { create(body()); var original=List.copyOf(phases); var b=body(); b.put("cntrct_ty_cd","IN"); update(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_id").isEmpty()); when(mapper.latestPart(1)).thenReturn(1L); update(body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_id").value(1)); assertThat(phases).isEqualTo(original); assertThat(parts).hasSize(1); }
    @Test void internalExternalWithoutRestorablePartIs400() throws Exception { var b=body(); b.put("cntrct_ty_cd","IN"); create(b); error(update(body()),"bsn_part_id"); assertThat(rows.get(1L).cntrct_ty_cd()).isEqualTo("IN"); }
    @Test void disabledUpdateHasNoMutation() throws Exception { create(body()); history.clear(); var old=rows.get(1L); rows.put(1L,row(1,old.prj_no(),old.input(skills.get(1L)),old.cur_cntrct_amt(),old.cur_end_dt(),old.cntrct_mm_qty(),"N")); error(update(body()),"use_yn"); assertThat(history).isEmpty(); verify(mapper,never()).update(any()); }
    @Test void sequentialUpdatesKeepLastName() throws Exception { create(body()); var b=body(); b.put("prj_nm","첫 이름"); update(b).andExpect(status().isOk()); b.put("prj_nm","마지막 이름"); update(b).andExpect(status().isOk()); mvc.perform(get("/api/projects/1")).andExpect(jsonPath("$.data.prj_nm").value("마지막 이름")); }
    @Test void marginEditUsesCurrentAmountAfterConfirmedChange() throws Exception {
        create(body());var old=rows.get(1L);
        rows.put(1L,row(1,old.prj_no(),old.input(skills.get(1L)),600000000L,old.cur_end_dt(),old.cntrct_mm_qty(),"Y"));
        when(mapper.confirmed(1)).thenReturn(true);
        var b=body();b.put("trgt_mrgn_rt",30);
        update(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.trgt_cst_amt").value(420000000));
    }
    @Test void performanceStartCanUseExtendedCurrentEndOnUpdate() throws Exception {
        create(body());var old=rows.get(1L);
        rows.put(1L,row(1,old.prj_no(),old.input(skills.get(1L)),old.cur_cntrct_amt(),LocalDate.of(2027,3,31),old.cntrct_mm_qty(),"Y"));
        when(mapper.confirmed(1)).thenReturn(true);
        var b=body();b.put("prfm_bgn_dt","2027-01-01");
        update(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.prfm_bgn_dt").value("2027-01-01"));
    }
}
