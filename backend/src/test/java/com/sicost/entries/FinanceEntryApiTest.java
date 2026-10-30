package com.sicost.entries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.*;
import static com.sicost.entries.FinanceEntryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=FinanceEntryController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,FinanceEntryService.class,CurrentUser.class,PermissionService.class})
class FinanceEntryApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean FinanceEntryMapper mapper;
    @MockBean Clock clock;
    final Map<Long,FinanceEntry> rows=new LinkedHashMap<>();
    final List<EntryAudit> audits=new ArrayList<>();
    final Set<Long> deleted=new HashSet<>();
    @BeforeEach void setup() {
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        rows.clear();audits.clear();deleted.clear();rows.put(7L,row(7,"2026-10",null,"N"));
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.list(anyString())).thenAnswer(i->rows.values().stream().filter(r->r.entry_ym().equals(i.getArgument(0))&&!deleted.contains(r.entry_id())).toList());
        when(mapper.row(anyLong())).thenAnswer(i->deleted.contains(i.getArgument(0))?null:rows.get(i.getArgument(0)));
        when(mapper.lockRow(anyLong())).thenAnswer(i->deleted.contains(i.getArgument(0))?null:rows.get(i.getArgument(0)));
        when(mapper.copiedSources(anyString())).thenAnswer(i->rows.values().stream().filter(r->r.entry_ym().equals(i.getArgument(0))).map(FinanceEntry::copy_entry_id).filter(Objects::nonNull).toList());
        when(mapper.insert(any())).thenAnswer(i->{var w=i.getArgument(0,EntryWrite.class);rows.put(8L,saved(8,w.input()));return 8L;});
        when(mapper.update(any())).thenAnswer(i->{var w=i.getArgument(0,EntryWrite.class);rows.put(w.id(),saved(w.id(),w.input()));return 1;});
        when(mapper.delete(any())).thenAnswer(i->{deleted.add(i.getArgument(0,EntryRemoval.class).id());return 1;});
        doAnswer(i->{audits.add(i.getArgument(0));return null;}).when(mapper).audit(any());
    }
    FinanceEntry row(long id,String month,Long source,String auto) {
        return new FinanceEntry(id,month,"SGA","임대료",300000,123,LocalDate.parse(month+"-30"),"Y","N","비고",source,auto,false);
    }
    FinanceEntry saved(long id,EntryInput p) {return new FinanceEntry(id,p.entry_ym(),p.fin_se_cd(),p.item_nm(),p.entry_amt(),p.vat_amt(),p.inout_dt(),p.rpt_yn(),p.inout_cmpl_yn(),p.remark(),p.copy_entry_id(),p.auto_fill_yn(),false);}
    Map<String,Object> body() {return new LinkedHashMap<>(Map.of("entry_ym","2026-10","fin_se_cd","SGA","item_nm","통신비","entry_amt",0));}
    ResultActions write(String method,Map<String,Object> input)throws Exception {
        var request=switch(method){case "POST"->post("/api/finance/entries");case "PUT"->put("/api/finance/entries/7");default->delete("/api/finance/entries/7");};
        return mvc.perform(request.contentType("application/json").content(json.writeValueAsString(input)));
    }
    void untouched(){verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).delete(any());assertThat(audits).isEmpty();}
    @Test void listIsPublicScopedAndDraftsDoNotCount()throws Exception {
        rows.put(6L,row(6,"2026-09",null,"N"));
        mvc.perform(get("/api/finance/entries").param("year","2026").param("month","10")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.saved_count").value(1)).andExpect(jsonPath("$.data.items[0].entry_id").value(7)).andExpect(jsonPath("$.data.drafts[0].auto_fill_yn").value("Y"));untouched();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"}) void savesZeroAndAuditsAllFields(String method)throws Exception {
        var b=body();b.put("remark","수정");b.put("vat_amt",17);b.put("inout_dt","2026-10-20");
        write(method,b).andExpect(status().isOk()).andExpect(jsonPath("$.data.entry_id").value(method.equals("POST")?8:7)).andExpect(jsonPath("$.data.entry_amt").value(0)).andExpect(jsonPath("$.data.vat_amt").value(17));
        assertThat(audits).hasSize(1);assertThat(json.readTree(audits.get(0).after()).get("remark").asText()).isEqualTo("수정");
        if(method.equals("PUT"))assertThat(json.readTree(audits.get(0).before()).get("entry_amt").asLong()).isEqualTo(300000);
    }
    @Test void deleteIsSoftAndKeepsHistory()throws Exception {
        write("DELETE",body()).andExpect(status().isOk());assertThat(rows).containsKey(7L);assertThat(deleted).contains(7L);assertThat(audits.get(0).before()).contains("임대료");
        mvc.perform(get("/api/finance/entries?year=2026&month=10")).andExpect(jsonPath("$.data.saved_count").value(0));
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"}) void closedOriginalOrTargetNeverChanges(String method)throws Exception {
        rows.put(7L,row(7,"2026-09",null,"N"));var b=body();if(method.equals("POST"))b.put("entry_ym","2026-09");
        write(method,b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));untouched();assertThat(rows.get(7L).entry_ym()).isEqualTo("2026-09");
    }
    @Test void movingIntoClosedDestinationIsRejected()throws Exception {var b=body();b.put("entry_ym","2026-09");write("PUT",b).andExpect(status().isConflict());untouched();}
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"}) void absentIs404(String method)throws Exception {rows.clear();write(method,body()).andExpect(status().isNotFound());untouched();}
    @ParameterizedTest @CsvSource({"entry_amt,-1","entry_amt,1.1","entry_amt,9223372036854775808","vat_amt,-1","item_nm,''","fin_se_cd,CORP_TAX","entry_ym,2026-13","inout_dt,2026-02-30","rpt_yn,X","inout_cmpl_yn,Y","auto_fill_yn,Y"})
    void validationIsFinanceError(String field,String value)throws Exception {
        var b=json.valueToTree(body());((com.fasterxml.jackson.databind.node.ObjectNode)b).set(field,field.endsWith("amt")?json.readTree(value):json.valueToTree(value));
        mvc.perform(post("/api/finance/entries").contentType("application/json").content(b.toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value(field));untouched();
    }
    @Test void invalidQueryIsValidationError()throws Exception {mvc.perform(get("/api/finance/entries?year=x&month=10")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));untouched();}
    @Test void futureMonthIsInvalid()throws Exception {var b=body();b.put("entry_ym","2026-11");write("POST",b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));untouched();}
    @Test void previewClampsMonthEndAndResetsCompletionWithoutWriting()throws Exception {
        rows.put(6L,new FinanceEntry(6L,"2026-01","NOI","이자",1,0,LocalDate.of(2026,1,31),"Y","Y",null,null,"N",false));
        mvc.perform(post("/api/finance/entries/copy-previous").param("month","2026-02").param("includeNonRecurring","false").contentType("application/json").content("{\"entry_ids\":[6]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].inout_dt").value("2026-02-28")).andExpect(jsonPath("$.data.items[0].inout_cmpl_yn").value("N")).andExpect(jsonPath("$.data.items[0].copy_entry_id").value(6));untouched();
    }
    @Test void previewMarksDeletedDuplicateAndSavingRejectsIt()throws Exception {
        rows.put(6L,row(6,"2026-09",null,"N"));rows.put(7L,row(7,"2026-10",6L,"Y"));deleted.add(7L);
        mvc.perform(post("/api/finance/entries/copy-previous?month=2026-10&includeNonRecurring=true").contentType("application/json").content("{\"entry_ids\":[6]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].already_copied").value(true));
        var b=body();b.put("copy_entry_id",6);write("POST",b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SOURCE"));untouched();
    }
    @Test void validAutoSourceIsSavedAndIncludedInTotals()throws Exception {rows.put(6L,row(6,"2026-09",null,"N"));var b=body();b.put("copy_entry_id",6);b.put("auto_fill_yn","Y");write("POST",b).andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_fill_yn").value("Y"));mvc.perform(get("/api/finance/entries?year=2026&month=10")).andExpect(jsonPath("$.data.saved_count").value(2));}
    @Test void invalidSourceIsRejected()throws Exception {var b=body();b.put("copy_entry_id",999);write("POST",b).andExpect(status().isNotFound());untouched();}
    @Test void previewMissingSelectedSourceIs404()throws Exception {mvc.perform(post("/api/finance/entries/copy-previous?month=2026-10&includeNonRecurring=true").contentType("application/json").content("{\"entry_ids\":[999]}" )).andExpect(status().isNotFound());untouched();}
    @Test void previewClosedTargetIs409()throws Exception {mvc.perform(post("/api/finance/entries/copy-previous?month=2026-09&includeNonRecurring=true").contentType("application/json").content("{}" )).andExpect(status().isConflict());untouched();}
    @Test void unexpectedFailureIsSafeFinanceError()throws Exception {when(mapper.list(anyString())).thenThrow(new IllegalStateException("private SQL"));mvc.perform(get("/api/finance/entries?year=2026&month=10")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private SQL"))));}
    @ParameterizedTest @CsvSource({"entry_amt,-1","vat_amt,-1","item_nm,''","fin_se_cd,CORP_TAX","rpt_yn,Z"})
    void updateValidatesBeforeChangingOriginal(String field,String value)throws Exception {
        var b=json.valueToTree(body());((com.fasterxml.jackson.databind.node.ObjectNode)b).set(field,field.endsWith("amt")?json.readTree(value):json.valueToTree(value));
        mvc.perform(put("/api/finance/entries/7").contentType("application/json").content(b.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value(field));
        assertThat(rows.get(7L).entry_amt()).isEqualTo(300000);untouched();
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"}) void invalidPathIdIs400(String method)throws Exception {
        var req=method.equals("PUT")?put("/api/finance/entries/0"):delete("/api/finance/entries/0");
        mvc.perform(req.contentType("application/json").content(json.writeValueAsString(body()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));untouched();
    }
    @Test void repeatedGetNeverPersistsAutomaticDrafts()throws Exception {
        rows.put(6L,row(6,"2026-09",null,"N"));
        for(int i=0;i<2;i++)mvc.perform(get("/api/finance/entries?year=2026&month=10")).andExpect(jsonPath("$.data.drafts.length()").value(1)).andExpect(jsonPath("$.data.saved_count").value(1));untouched();
    }
    @Test void previewExcludesNonRecurringUnlessRequested()throws Exception {
        rows.put(6L,new FinanceEntry(6L,"2026-09","NOE","일회",1,0,null,"N","N",null,null,"N",false));
        mvc.perform(post("/api/finance/entries/copy-previous?month=2026-10&includeNonRecurring=false").contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(post("/api/finance/entries/copy-previous?month=2026-10&includeNonRecurring=true").contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));untouched();
    }
    @Test void nonRecurringCannotForgeAutomaticSource()throws Exception {
        rows.put(6L,new FinanceEntry(6L,"2026-09","NOE","일회",1,0,null,"N","N",null,null,"N",false));
        var b=body();b.put("copy_entry_id",6);b.put("auto_fill_yn","Y");write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("auto_fill_yn"));untouched();
    }
    @Test void wrongMonthSourceIsRejected()throws Exception {rows.put(6L,row(6,"2026-08",null,"N"));var b=body();b.put("copy_entry_id",6);write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("copy_entry_id"));untouched();}
    @Test void copyProvenanceSurvivesEditingAfterSourceDeletion()throws Exception {
        rows.put(7L,row(7,"2026-10",6L,"Y"));write("PUT",body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.copy_entry_id").value(6)).andExpect(jsonPath("$.data.auto_fill_yn").value("Y"));
    }
    @Test void databaseCopyRaceHasContractConflict()throws Exception {
        rows.put(6L,row(6,"2026-09",null,"N"));doThrow(new org.springframework.dao.DuplicateKeyException("private constraint")).when(mapper).insert(any());
        var b=body();b.put("copy_entry_id",6);write("POST",b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SOURCE"));assertThat(audits).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"{\"entry_ids\":[0]}","{\"entry_ids\":[6,6]}","{\"entry_ids\":null}","[]","{} {}"})
    void previewRejectsMalformedSelections(String body)throws Exception {mvc.perform(post("/api/finance/entries/copy-previous?month=2026-10&includeNonRecurring=true").contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));untouched();}
    @Test void previewFutureTargetIs409()throws Exception {mvc.perform(post("/api/finance/entries/copy-previous?month=2026-11&includeNonRecurring=true").contentType("application/json").content("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));untouched();}
}
