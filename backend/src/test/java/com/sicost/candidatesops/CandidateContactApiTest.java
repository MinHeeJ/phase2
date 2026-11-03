package com.sicost.candidatesops;

import static com.sicost.candidatesops.CandidateContactModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.workforce.CandidateForecastReader;
import com.sicost.developers.*;
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

@WebMvcTest(controllers=CandidateContactController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,CandidateContactService.class,CandidateShortageService.class,
    DeveloperService.class,CurrentUser.class,PermissionService.class,CandidateContactApiTest.Time.class})
class CandidateContactApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneOffset.UTC);} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean CandidateContactMapper mapper;
    @MockBean DeveloperMapper developers;
    @MockBean CandidateForecastReader forecast;
    CandidateRow row;
    List<Contact> history;
    @BeforeEach void setup() {
        row=new CandidateRow(7,"CAND-0007","후보","FL",null,"BE","SR",6000000L,LocalDate.of(2026,11,1),"NONE",null,"REF",null,null,null,"Y",LocalDateTime.of(2026,10,1,0,0));
        history=new ArrayList<>();
        when(mapper.lock(7)).thenAnswer(i->row);when(mapper.row(7)).thenAnswer(i->row);
        when(mapper.skills(7)).thenReturn(List.of("JAVA"));
        when(mapper.contacts(7)).thenAnswer(i->history.stream().sorted(Comparator.comparing(Contact::cntct_dt).thenComparing(Contact::created_at).reversed()).toList());
        when(developers.activeCode(any())).thenReturn(true);when(developers.activePartner(anyLong())).thenReturn(true);
        when(developers.nextId()).thenReturn(42L);
        when(developers.row(42)).thenReturn(new DeveloperModels.DeveloperRow(42,"K-0042","후보","FL","BE",null,null,"DIRECT","SR",null,null,null,null,null,null,null));
        when(developers.summary(any())).thenReturn(new DeveloperModels.DeveloperListItem(42,"K-0042","후보","자사","FL","프리랜서","SR","고급","BE","백엔드","대기",null,0,BigDecimal.ZERO,false,null,null,"Y",null));
        doAnswer(i->{var w=i.getArgument(0,ContactWrite.class);history.add(new Contact(w.input().cntct_dt(),w.input().cntct_stts_cd(),w.input().memo(),w.actor(),LocalDateTime.of(2026,10,23,0,0).plusNanos(history.size())));return null;}).when(mapper).append(any());
        when(mapper.refreshContact(any())).thenAnswer(i->{var latest=history.stream().max(Comparator.comparing(Contact::cntct_dt).thenComparing(Contact::created_at)).orElseThrow();row=state(row.dev_id()!=null?"SIGNED":latest.cntct_stts_cd(),latest.cntct_dt(),row.dev_id());return 1;});
        when(mapper.convert(any())).thenAnswer(i->{var w=i.getArgument(0,ConversionWrite.class);row=state("SIGNED",row.last_cntct_dt(),w.developerId());return 1;});
        when(forecast.candidateMonths(anyMap())).thenReturn(List.of(new CandidateForecastReader.Month("2026-11","BE","SR",new BigDecimal("0.41"))));
        when(mapper.candidateCount(any())).thenReturn(2L);
    }
    CandidateRow state(String status,LocalDate date,Long dev) {return new CandidateRow(row.cand_id(),row.cand_no(),row.cand_nm(),row.cand_se_cd(),row.vndr_ptnr_id(),row.job_cd(),row.grd_cd(),row.hope_untprc_amt(),row.avail_dt(),status,date,row.source(),row.tel_no(),row.rmrk_desc(),dev,row.use_yn(),row.created_at());}
    Map<String,Object> contact(String date,String status) {return new LinkedHashMap<>(Map.of("cntct_dt",date,"cntct_stts_cd",status,"memo","연락 내용"));}
    Map<String,Object> conversion() {return Map.of("developer",new LinkedHashMap<>(Map.of("dev_nm","충돌하는 이름","emp_se_cd","FT","main_job_cd","FE","grd_cd","JR","skill",List.of("REACT"))));}
    ResultActions postBody(String path,Object body)throws Exception {return mvc.perform(post(path).contentType("application/json").content(json.writeValueAsString(body)));}
    ResultActions add(String date,String status)throws Exception{return postBody("/api/candidates/7/contacts",contact(date,status));}
    void bad(ResultActions response,String field)throws Exception {response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());}
    void noWrites(){verify(mapper,never()).append(any());verify(mapper,never()).refreshContact(any());verify(mapper,never()).convert(any());verify(developers,never()).insert(any());}
    @Test void anonymousContactReturns200AndSavedHistoryWithNullRecorder()throws Exception {
        add("2026-10-20","INTV").andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.last_cntct_dt").value("2026-10-20")).andExpect(jsonPath("$.data.cntct_stts_cd").value("INTV")).andExpect(jsonPath("$.data.contacts[0].memo").value("연락 내용")).andExpect(jsonPath("$.data.contacts[0].user_id").isEmpty());
        assertThat(history).hasSize(1);var order=inOrder(mapper);order.verify(mapper).lock(7);order.verify(mapper).append(any());order.verify(mapper).refreshContact(any());
    }
    @Test void backdatedInsertPreservesCurrentStatusAndEveryOriginalField()throws Exception {
        add("2026-10-20","INTV");var original=List.copyOf(history);
        add("2026-10-10","DONE").andExpect(status().isOk()).andExpect(jsonPath("$.data.cntct_stts_cd").value("INTV")).andExpect(jsonPath("$.data.last_cntct_dt").value("2026-10-20"));
        assertThat(history).hasSize(2).containsAll(original);
    }
    @Test void sameDateLatestRegistrationWinsAndBothRecordsRemain()throws Exception {
        add("2026-10-20","INTV");add("2026-10-20","HOLD").andExpect(jsonPath("$.data.cntct_stts_cd").value("HOLD"));assertThat(history).hasSize(2);
    }
    @Test void seoulTodayAcceptsUtcTomorrowButRejectsFuture()throws Exception {
        add("2026-10-23","DONE").andExpect(status().isOk());reset(mapper);bad(add("2026-10-24","DONE"),"cntct_dt");noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "LONG"}) void invalidMemoIs400AndUnchanged(String memo)throws Exception {
        var before=row;var body=contact("2026-10-20","DONE");body.put("memo",memo.equals("LONG")?"가".repeat(1001):memo);bad(postBody("/api/candidates/7/contacts",body),"memo");assertThat(row).isEqualTo(before);assertThat(history).isEmpty();noWrites();
    }
    @Test void maxMemoIsAccepted()throws Exception {var body=contact("2026-10-20","DONE");body.put("memo","가".repeat(1000));postBody("/api/candidates/7/contacts",body).andExpect(status().isOk());}
    @Test void unknownContactStatusIsRejected()throws Exception {bad(add("2026-10-20","OTHER"),"cntct_stts_cd");noWrites();}
    @Test void inactiveContactCodeIsRejected()throws Exception {when(developers.activeCode(new DeveloperModels.DeveloperCode("CONTACT_STATUS","DONE"))).thenReturn(false);bad(add("2026-10-20","DONE"),"cntct_stts_cd");noWrites();}
    @Test void malformedDateIsRejected()throws Exception {bad(add("2026-02-30","DONE"),"cntct_dt");noWrites();}
    @ParameterizedTest @ValueSource(strings={"contacts","convert"}) void missingCandidateIs404(String operation)throws Exception {postBody("/api/candidates/99/"+operation,operation.equals("contacts")?contact("2026-10-20","DONE"):conversion()).andExpect(status().isNotFound());noWrites();}
    @Test void anonymousConversionUsesExistingDeveloperServiceAndCandidateOwnedFields()throws Exception {
        postBody("/api/candidates/7/convert",conversion()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.candidate.dev_id").value(42)).andExpect(jsonPath("$.data.candidate.cntct_stts_cd").value("SIGNED")).andExpect(jsonPath("$.data.developer.dev_id").value(42));
        verify(developers).insert(argThat(w->w.input().dev_nm().equals("후보")&&w.input().emp_se_cd().equals("FL")&&w.input().main_job_cd().equals("BE")&&w.input().grd_cd().equals("SR")&&w.input().skill().equals(List.of("JAVA"))));
        verify(developers).appendHistory(argThat(h->h.type().equals("INSERT")&&h.actor()==null));
    }
    @Test void missingEmployeeTypeHasNoDeveloperOrCandidateWrites()throws Exception {bad(postBody("/api/candidates/7/convert",Map.of("developer",Map.of())),"emp_se_cd");noWrites();}
    @Test void osWithoutCandidateVendorIsRejectedAndSuppliedVendorCannotOverride()throws Exception {
        row=new CandidateRow(7,row.cand_no(),row.cand_nm(),"OS",null,row.job_cd(),row.grd_cd(),row.hope_untprc_amt(),row.avail_dt(),row.cntct_stts_cd(),null,null,null,null,null,"Y",row.created_at());
        bad(postBody("/api/candidates/7/convert",conversion()),"vndr_ptnr_id");noWrites();
    }
    @Test void secondConversionIs409AndDeveloperCreatedOnce()throws Exception {
        postBody("/api/candidates/7/convert",conversion()).andExpect(status().isOk());postBody("/api/candidates/7/convert",conversion()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CONVERTED"));verify(developers,times(1)).insert(any());
    }
    @Test void convertedStatusCannotBeUndoneByBackdatedOrNewContact()throws Exception {postBody("/api/candidates/7/convert",conversion());add("2026-10-20","DONE").andExpect(jsonPath("$.data.cntct_stts_cd").value("SIGNED"));assertThat(history).hasSize(1);}
    @Test void malformedJsonHasNoWrites()throws Exception {mvc.perform(post("/api/candidates/7/contacts").contentType("application/json").content("{} trailing")).andExpect(status().isBadRequest());noWrites();}
    @Test void unknownBodyFieldIsRejected()throws Exception {var b=contact("2026-10-20","DONE");b.put("user_id",5);bad(postBody("/api/candidates/7/contacts",b),"user_id");noWrites();}
    @Test void anonymousShortagesPreserveCommonRawDecimalsAndQueryFilters()throws Exception {
        for(int i=0;i<2;i++)mvc.perform(get("/api/candidates/shortages").param("from","2026-11").param("job","BE").param("grade","SR")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.items[0].maximum_shortage_mm").value(0.41)).andExpect(jsonPath("$.data.items[0].maximum_shortage_people").value(1)).andExpect(jsonPath("$.data.items[0].candidate_count").value(2)).andExpect(jsonPath("$.data.items[0].recommended_hiring_month").value("2026-09")).andExpect(jsonPath("$.data.items[0].review_immediately").value(true));
        verify(forecast,times(2)).candidateMonths(Map.of("from","2026-11","months","6","job","BE","grade","SR"));noWrites();
    }
    @Test void exactlyPointFortyIsNotShortage()throws Exception {when(forecast.candidateMonths(anyMap())).thenReturn(List.of(new CandidateForecastReader.Month("2026-11","BE",null,new BigDecimal("0.40"))));mvc.perform(get("/api/candidates/shortages")).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));verify(mapper,never()).candidateCount(any());noWrites();}
    @Test void yearWithoutFutureMonthsReturnsEmpty()throws Exception {mvc.perform(get("/api/candidates/shortages").param("year","2025")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));noWrites();}
    @ParameterizedTest @CsvSource({"from,2026-13","from,2026-09","months,7","sort,invalid","size,30","page,-1","year,0","job,not allowed","unknown,x"})
    void invalidShortageFiltersDoNotReadForecastOrWrite(String field,String value)throws Exception {bad(mvc.perform(get("/api/candidates/shortages").param(field,value)),field);verifyNoInteractions(forecast);noWrites();}
    @Test void invalidCodeFilterDoesNotReadForecast()throws Exception {when(developers.activeCode(new DeveloperModels.DeveloperCode("JOB","OTHER"))).thenReturn(false);bad(mvc.perform(get("/api/candidates/shortages").param("job","OTHER")),"job");verifyNoInteractions(forecast);noWrites();}
    @Test void pagingKeepsFilteredTotalAndStableOrder()throws Exception {
        when(forecast.candidateMonths(anyMap())).thenReturn(java.util.stream.IntStream.range(0,23).mapToObj(i->new CandidateForecastReader.Month("2026-11",String.format("JOB%02d",i),null,new BigDecimal("0.41"))).toList());
        mvc.perform(get("/api/candidates/shortages").param("page","1").param("size","20")).andExpect(jsonPath("$.data.total").value(23)).andExpect(jsonPath("$.data.items.length()").value(3)).andExpect(jsonPath("$.data.items[0].job_cd").value("JOB20"));noWrites();
    }
}
