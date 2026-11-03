package com.sicost.candidates;

import static com.sicost.candidates.CandidateModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.developers.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CandidateController.class)
@Import({CandidateService.class,CurrentUser.class,PermissionService.class,ApiExceptionHandler.class,RequestIdFilter.class,CandidateApiTest.Time.class})
class CandidateApiTest {
    @TestConfiguration static class Time {@Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:30:00Z"),ZoneOffset.UTC);}}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean CandidateMapper mapper;
    @MockBean DeveloperService developers;
    CandidateRow row(Long developer){return new CandidateRow(73,"CAND-0073","후보","FL",null,"BE",null,5000000L,LocalDate.of(2026,11,1),developer==null?"NONE":"SIGNED","01012345678",null,null,"Y",developer,null,LocalDateTime.of(2026,9,1,0,0));}
    @BeforeEach void setup(){when(mapper.row(73)).thenReturn(row(null));when(mapper.lock(73)).thenReturn(row(null));when(mapper.nextId()).thenReturn(73L);when(mapper.activeCode(any())).thenReturn(true);when(mapper.kpi(any())).thenReturn(new CandidateKpi(0,0,0,0));}
    Map<String,Object> body(){return new LinkedHashMap<>(Map.of("cand_nm","후보","cand_se_cd","FL","job_cd","BE","hope_untprc_amt",5000000,"avail_dt","2026-11-01","skill",List.of("JAVA")));}
    @Test void anonymousListFiltersAndKpiAreNotPageDependent()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(row(null)));when(mapper.count(any())).thenReturn(51L);when(mapper.kpi(any())).thenReturn(new CandidateKpi(51,2,10,4));
        mvc.perform(get("/api/candidates").param("job","BE").param("page","1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.total").value(51))
            .andExpect(jsonPath("$.data.kpi.candidate_count").value(51)).andExpect(jsonPath("$.data.items[0].tel_no").value("***"));
        verify(mapper).list(argThat(q->q.job().equals("BE")&&q.offset()==50&&q.today().equals(LocalDate.of(2026,10,23))));
        verify(mapper,never()).audit(any());
    }
    @Test void createUsesAllocatedNumberNullActorAndReadback()throws Exception {
        mvc.perform(post("/api/candidates").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.cand_no").value("CAND-0073")).andExpect(jsonPath("$.data.skill").isArray());
        verify(mapper).insert(argThat(w->w.no().equals("CAND-0073")&&w.input().vndr_ptnr_id()==null&&w.actor()==null));verify(mapper).insertSkill(new CandidateChild(73,"JAVA"));verify(mapper).row(73);
    }
    @Test void outsourcedWithoutPartnerDoesNotWrite()throws Exception {
        var b=body();b.put("cand_se_cd","OS");mvc.perform(post("/api/candidates").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("vndr_ptnr_id"));verify(mapper,never()).insert(any());
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-24","2026-13-01"})
    void invalidContactDoesNotAppend(String date)throws Exception {
        mvc.perform(post("/api/candidates/73/contacts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("cntct_dt",date,"cntct_stts_cd","DONE","memo","연락"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cntct_dt"));verify(mapper,never()).appendContact(any());
    }
    @Test void oldContactIsAppendOnlyAndProjectionRunsAfterInsert()throws Exception {
        mvc.perform(post("/api/candidates/73/contacts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("cntct_dt","2026-10-10","cntct_stts_cd","DONE","memo","과거 연락"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.contacts").isArray());
        var order=inOrder(mapper);order.verify(mapper).lock(73);order.verify(mapper).appendContact(argThat(w->w.input().cntct_dt().equals(LocalDate.of(2026,10,10))&&w.actor()==null));order.verify(mapper).projectLatestContact(73);
    }
    @Test void missingCandidateIs404()throws Exception {mvc.perform(get("/api/candidates/99")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));}
    @Test void convertedCandidateCannotCreateAnotherDeveloper()throws Exception {
        when(mapper.lock(73)).thenReturn(row(91L));mvc.perform(post("/api/candidates/73/convert").contentType(MediaType.APPLICATION_JSON).content("{\"developer\":{}}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CONVERTED"));verifyNoInteractions(developers);verify(mapper,never()).convert(any());
    }
    @Test void maskedEditCannotOverwriteStoredPhoneOrHistory()throws Exception {
        var b=body();b.put("tel_no","***");mvc.perform(put("/api/candidates/73").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isOk());verify(mapper).update(argThat(w->w.input().tel_no().equals("01012345678")));verify(mapper,never()).appendContact(any());
    }
    @Test void invalidSortReturnsSafeError()throws Exception {
        mvc.perform(get("/api/candidates").param("sort","cand_id;drop table hr.cand,asc"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort")).andExpect(jsonPath("$.requestId").isNotEmpty());verify(mapper,never()).list(any());
    }
    @Test void conversionUsesCandidateIdentityAndExistingDeveloperService()throws Exception {
        var item=new DeveloperModels.DeveloperListItem(91,"K-0091","후보","자사","FL","프리랜서",null,null,"BE","백엔드","대기",null,0,java.math.BigDecimal.ZERO,false,null,5000000L,"Y",null);
        var developer=new DeveloperModels.DeveloperDetail(item,null,null,"DIRECT",List.of(),List.of("JAVA"),null,null,"01012345678",null,null,null,null,Map.of(),null);
        when(mapper.skills(73)).thenReturn(List.of("JAVA"));when(mapper.row(73)).thenReturn(row(91L));when(developers.create(any())).thenReturn(developer);
        mvc.perform(post("/api/candidates/73/convert").contentType(MediaType.APPLICATION_JSON)
            .content("{\"developer\":{\"dev_nm\":\"다른 이름\",\"emp_se_cd\":\"FT\",\"main_job_cd\":\"FE\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidate.dev_id").value(91))
            .andExpect(jsonPath("$.data.candidate.cntct_stts_cd").value("SIGNED"))
            .andExpect(jsonPath("$.data.developer.dev_id").value(91));
        verify(developers).create(argThat(d->d.dev_nm().equals("후보")&&d.emp_se_cd().equals("FL")&&d.main_job_cd().equals("BE")&&d.skill().equals(List.of("JAVA"))));
        verify(mapper).convert(new Conversion(73,91,null));verify(mapper).linkDeveloper(new Conversion(73,91,null));
    }
    @Test void infrastructureFailureDoesNotLeakSqlOrContact()throws Exception {
        when(mapper.row(73)).thenThrow(new IllegalStateException("SELECT tel_no FROM hr.cand 01012345678"));
        mvc.perform(get("/api/candidates/73")).andExpect(status().isInternalServerError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("01012345678"))));
    }
}
