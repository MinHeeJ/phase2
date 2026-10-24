package com.sicost.workstatus;

import static com.sicost.workstatus.WorkStatusModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers=WorkStatusController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,WorkStatusService.class,CurrentUser.class,PermissionService.class,WorkStatusApiTest.Time.class})
class WorkStatusApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean WorkStatusMapper mapper;
    final String url="/api/developers/1/work-status";
    Map<Long,WorkStatusRow> rows;
    LocalDate resignation;
    List<WorkStatusAudit> audits;
    @BeforeEach void setup()throws Exception {
        rows=new LinkedHashMap<>();audits=new ArrayList<>();resignation=null;
        when(mapper.developerExists(1)).thenReturn(true);
        when(mapper.nextId()).thenReturn(10L);
        when(mapper.detail(any())).thenAnswer(a->{WorkStatusKey k=a.getArgument(0);var r=rows.get(k.histId());return r!=null&&r.dev_id()==k.devId()?r:null;});
        when(mapper.list(any())).thenAnswer(a->{WorkStatusQuery q=a.getArgument(0);return matching(q).stream().skip(q.offset()).limit(q.size()).toList();});
        when(mapper.count(any())).thenAnswer(a->(long)matching(a.getArgument(0)).size());
        when(mapper.insert(any())).thenAnswer(a->{WorkStatusWrite w=a.getArgument(0);var r=row(w);rows.put(w.histId(),r);return r;});
        when(mapper.update(any())).thenAnswer(a->{WorkStatusWrite w=a.getArgument(0);var r=row(w);rows.put(w.histId(),r);return r;});
        when(mapper.delete(any())).thenAnswer(a->rows.remove(((WorkStatusKey)a.getArgument(0)).histId())==null?0:1);
        when(mapper.snapshot(any())).thenAnswer(a->json.writeValueAsString(rows.get(((WorkStatusKey)a.getArgument(0)).histId())));
        doAnswer(a->{resignation=((WorkStatusResignation)a.getArgument(0)).date();return null;}).when(mapper).resignation(any());
        when(mapper.resignationDate(1)).thenAnswer(a->resignation);
        doAnswer(a->{audits.add(a.getArgument(0));return null;}).when(mapper).audit(any());
    }
    List<WorkStatusRow> matching(WorkStatusQuery q) {
        return rows.values().stream().filter(r->r.dev_id()==q.devId()&&(q.status()==null||q.status().equals(r.work_stts_cd())))
            .sorted(Comparator.comparing(WorkStatusRow::bgn_dt).reversed()).toList();
    }
    WorkStatusRow row(WorkStatusWrite w) {var i=w.input();return new WorkStatusRow(w.histId(),w.devId(),i.work_stts_cd(),i.bgn_dt(),i.end_dt(),i.paid_yn(),i.rmrk_desc());}
    WorkStatusRow existing(String code,String start,String end) {
        var r=new WorkStatusRow(2,1,code,LocalDate.parse(start),end==null?null:LocalDate.parse(end),"N","원래 메모");rows.put(2L,r);return r;
    }
    Map<String,Object> body() {var b=new LinkedHashMap<String,Object>();b.put("work_stts_cd","PARENTAL");b.put("bgn_dt","2026-10-01");b.put("end_dt","2026-10-31");return b;}
    ResultActions write(String method,Map<String,Object> body)throws Exception {
        return mvc.perform((method.equals("POST")?post(url):put(url+"/2")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    void untouched(){verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).delete(any());verify(mapper,never()).audit(any());verify(mapper,never()).resignation(any());}
    @Test void publicListReadsLatestFirstAndNullEndWithoutWrites()throws Exception {
        existing("LEAVE","2026-10-01",null);
        rows.put(3L,new WorkStatusRow(3,1,"VACATION",LocalDate.of(2026,8,1),LocalDate.of(2026,8,2),"Y","휴가 메모"));
        for(int n=0;n<2;n++)mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].work_stts_hist_id").value(2))
            .andExpect(jsonPath("$.data.items[0].end_dt").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.items[1].paid_yn").value("Y"));
        verify(mapper,times(2)).list(argThat(q->q.order().equals("bgn_dt DESC, work_stts_hist_id DESC")));untouched();
    }
    @Test void emptyHistoryIsItemsArray()throws Exception {mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());untouched();}
    @Test void filteredListAndCountShareQueryAndAdaptZeroBasedPaging()throws Exception {
        existing("LEAVE","2026-10-01",null);
        mvc.perform(get(url).param("status","LEAVE").param("page","1").param("size","20").param("sort","end_dt,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.page").value(1));
        var capture=org.mockito.ArgumentCaptor.forClass(WorkStatusQuery.class);verify(mapper).list(capture.capture());verify(mapper).count(capture.getValue());assertThat(capture.getValue().offset()).isEqualTo(20);assertThat(capture.getValue().status()).isEqualTo("LEAVE");untouched();
    }
    @ParameterizedTest @CsvSource({"status,WRONG","page,-1","size,30","sort,bogus","sort,'bgn_dt;drop,asc'","page,2147483647"})
    void badQueryHasSingularFieldAndRequestId(String field,String value)throws Exception {
        mvc.perform(get(url).param(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());untouched();
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc"})
    void invalidDeveloperPath(String id)throws Exception {mvc.perform(get("/api/developers/"+id+"/work-status")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));untouched();}
    @Test void missingDeveloperIs404()throws Exception {mvc.perform(get("/api/developers/99/work-status")).andExpect(status().isNotFound());untouched();}
    @Test void anonymousCreateReturns201AndCanBeReadBack()throws Exception {
        mvc.perform(post("/api/developers/1/work-status").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.dev_id").value(1)).andExpect(jsonPath("$.data.work_stts_hist_id").value(10));
        mvc.perform(get("/api/developers/1/work-status")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].work_stts_cd").value("PARENTAL"));
        assertThat(audits).hasSize(1);assertThat(audits.get(0).type()).isEqualTo("INSERT");assertThat(json.readTree(audits.get(0).after()).get("dev_id").asLong()).isEqualTo(1);
        verify(mapper,never()).closed(any());
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void missingStartUsesExactMessage(String method)throws Exception {existing("LEAVE","2026-10-01",null);var b=body();b.remove("bgn_dt");write(method,b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bgn_dt")).andExpect(jsonPath("$.message").value("시작일을 입력해 주세요."));untouched();}
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void reverseDatesKeepOriginal(String method)throws Exception {var old=existing("LEAVE","2026-10-01",null);var b=body();b.put("end_dt","2026-09-30");write(method,b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("end_dt")).andExpect(jsonPath("$.message").value("종료일은 시작일 이후여야 합니다."));assertThat(rows.get(2L)).isEqualTo(old);untouched();}
    @ParameterizedTest @CsvSource({"work_stts_cd,UNKNOWN","work_stts_cd,''","paid_yn,X","bgn_dt,2026-02-30","end_dt,invalid","extra,x"})
    void rejectsInvalidFields(String field,String value)throws Exception {var b=body();b.put(field,value);write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void rejectsOverlongMemo()throws Exception {var b=body();b.put("rmrk_desc","가".repeat(501));write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rmrk_desc"));untouched();}
    @Test void acceptsFiveHundredCharacterMemoAndOptionalEnd()throws Exception {var b=body();b.remove("end_dt");b.put("rmrk_desc","가".repeat(500));write("POST",b).andExpect(status().isCreated()).andExpect(jsonPath("$.data.end_dt").value(org.hamcrest.Matchers.nullValue()));}
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void overlapUses409AndKeepsState(String method)throws Exception {
        var old=existing("LEAVE","2026-10-01",null);when(mapper.overlap(any())).thenReturn(old);when(mapper.codeName("LEAVE")).thenReturn("휴직");
        write(method,body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_OVERLAP")).andExpect(jsonPath("$.message").value("다른 근무 상태(휴직 2026-10-01~–)와 기간이 겹칩니다."));assertThat(rows.get(2L)).isEqualTo(old);untouched();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void secondResignationIsNotPeriodOverlap(String method)throws Exception {
        existing("LEAVE","2026-10-01",null);when(mapper.resignationExists(any())).thenReturn(true);var b=body();b.put("work_stts_cd","RESIGN");
        write(method,b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.field").value("work_stts_cd")).andExpect(jsonPath("$.message").value("이미 퇴사가 등록되어 있습니다."));untouched();
    }
    @Test void resignationCreateNormalizesAndSynchronizesDateAndInsertAudit()throws Exception {
        var b=body();b.put("work_stts_cd","RESIGN");b.put("bgn_dt","2026-10-20");b.put("paid_yn","Y");
        mvc.perform(post("/api/developers/1/work-status").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.end_dt").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.data.paid_yn").value("N"));
        assertThat(resignation).isEqualTo(LocalDate.of(2026,10,20));assertThat(audits.get(0).histId()).isEqualTo(10);
    }
    @Test void vacationPaidDefaultsNAndCanBeY()throws Exception {var b=body();b.put("work_stts_cd","VACATION");write("POST",b).andExpect(status().isCreated()).andExpect(jsonPath("$.data.paid_yn").value("N"));b.put("paid_yn","Y");write("POST",b).andExpect(status().isCreated()).andExpect(jsonPath("$.data.paid_yn").value("Y"));}
    @Test void nonVacationPaidIsNormalized()throws Exception {var b=body();b.put("paid_yn","Y");write("POST",b).andExpect(status().isCreated()).andExpect(jsonPath("$.data.paid_yn").value("N"));}
    @Test void anonymousUpdateReadbackAndCompleteAudit()throws Exception {
        existing("LEAVE","2026-10-01",null);var b=body();b.put("rmrk_desc","바뀐 메모");
        mvc.perform(put("/api/developers/1/work-status/2").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.work_stts_hist_id").value(2));
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].rmrk_desc").value("바뀐 메모"));
        assertThat(audits.get(0).type()).isEqualTo("UPDATE");assertThat(json.readTree(audits.get(0).before()).get("rmrk_desc").asText()).isEqualTo("원래 메모");assertThat(json.readTree(audits.get(0).after()).get("dev_id").asLong()).isEqualTo(1);
        verify(mapper).overlap(argThat(w->w.histId()==2));
    }
    @Test void typeTransitionsAndDateEditSynchronizeResignation()throws Exception {
        existing("LEAVE","2026-10-01",null);var b=body();b.put("work_stts_cd","RESIGN");write("PUT",b).andExpect(status().isOk());assertThat(resignation).isEqualTo(LocalDate.of(2026,10,1));
        b.put("bgn_dt","2026-10-20");write("PUT",b).andExpect(status().isOk());assertThat(resignation).isEqualTo(LocalDate.of(2026,10,20));
        b.put("work_stts_cd","LEAVE");write("PUT",b).andExpect(status().isOk());assertThat(resignation).isNull();
    }
    @Test void updateChecksOldIntervalEvenWhenReplacementIsOpen()throws Exception {
        var old=existing("LEAVE","2026-09-30","2026-10-01");when(mapper.closed(argThat(i->i!=null&&i.bgn_dt().equals(old.bgn_dt())))).thenReturn(true);
        mvc.perform(put("/api/developers/1/work-status/2").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));assertThat(rows.get(2L)).isEqualTo(old);untouched();
    }
    @Test void updateChecksRequestedIntervalToo()throws Exception {
        var old=existing("LEAVE","2026-08-01","2026-08-02");when(mapper.closed(argThat(i->i!=null&&i.bgn_dt().equals(LocalDate.of(2026,10,1))))).thenReturn(true);
        write("PUT",body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));assertThat(rows.get(2L)).isEqualTo(old);untouched();
    }
    @Test void anonymousDeleteReadbackPreservesOtherRowsAndDeletesAuditWithOwner()throws Exception {
        existing("RESIGN","2026-10-20",null);resignation=LocalDate.of(2026,10,20);rows.put(3L,new WorkStatusRow(3,1,"VACATION",LocalDate.of(2026,8,1),null,"N",null));
        mvc.perform(delete("/api/developers/1/work-status/2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true)).andExpect(jsonPath("$.data.rsgn_dt").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].work_stts_hist_id").value(3));
        assertThat(audits.get(0).type()).isEqualTo("DELETE");assertThat(audits.get(0).after()).isNull();assertThat(json.readTree(audits.get(0).before()).get("dev_id").asLong()).isEqualTo(1);
    }
    @Test void zeroHistoryIdDeleteIs400WithoutMutation()throws Exception {existing("LEAVE","2026-10-01",null);mvc.perform(delete("/api/developers/1/work-status/0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("histId"));untouched();}
    @Test void zeroHistoryIdUpdateIs400WithoutMutation()throws Exception {mvc.perform(put("/api/developers/1/work-status/0").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("histId"));untouched();}
    @Test void closedDeleteKeepsRowResignationAndAudit()throws Exception {
        var old=existing("RESIGN","2026-09-20",null);resignation=old.bgn_dt();when(mapper.closed(any())).thenReturn(true);
        mvc.perform(delete("/api/developers/1/work-status/2")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));assertThat(rows.get(2L)).isEqualTo(old);assertThat(resignation).isEqualTo(old.bgn_dt());assertThat(audits).isEmpty();untouched();
    }
    @Test void deleteAndUpdateCannotUseAnotherDevelopersHistory()throws Exception {
        rows.put(2L,new WorkStatusRow(2,99,"LEAVE",LocalDate.of(2026,10,1),null,"N",null));
        mvc.perform(delete(url+"/2")).andExpect(status().isNotFound());write("PUT",body()).andExpect(status().isNotFound());untouched();
    }
    @Test void malformedJsonAndUnknownFieldsNeverWrite()throws Exception {mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{} {}")).andExpect(status().isBadRequest());untouched();}
}
