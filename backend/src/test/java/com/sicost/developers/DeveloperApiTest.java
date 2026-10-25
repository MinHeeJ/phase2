package com.sicost.developers;

import static com.sicost.developers.DeveloperModels.*;
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

@WebMvcTest(controllers=DeveloperController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,DeveloperService.class,CurrentUser.class,PermissionService.class,DeveloperApiTest.Time.class})
class DeveloperApiTest {
    @TestConfiguration static class Time {@Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:30:00Z"),ZoneOffset.UTC);}}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean DeveloperMapper mapper;
    DeveloperRow row=new DeveloperRow(1,"K-0001","개발자","FT","BE",null,null,"DIRECT",null,null,null,null,null,null,null,null);
    DeveloperListItem item=new DeveloperListItem(1,"K-0001","개발자","자사","FT","정규직",null,null,"BE","백엔드","대기",null,0,new BigDecimal("0.00"),false,null,null,"Y",null);
    @BeforeEach void setup() {
        when(mapper.row(1)).thenReturn(row);when(mapper.summary(any())).thenReturn(item);when(mapper.activeCode(any())).thenReturn(true);
        when(mapper.activePartner(anyLong())).thenReturn(true);when(mapper.activePart(anyLong())).thenReturn(true);when(mapper.nextId()).thenReturn(1L);
    }
    Map<String,Object> body(){var b=new LinkedHashMap<String,Object>();b.put("dev_nm","개발자");b.put("emp_se_cd","FT");b.put("main_job_cd","BE");b.put("sub_job",List.of());b.put("skill",List.of());return b;}
    ResultActions write(String method,Map<String,Object> b)throws Exception{return mvc.perform((method.equals("POST")?post("/api/developers"):put("/api/developers/1")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));}
    void untouched(){verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).appendHistory(any());verify(mapper,never()).deleteJobs(anyLong());verify(mapper,never()).deleteSkills(anyLong());}
    @Test void anonymousListCanBeReadAgainWithDefaultZeroPageAndSeoulDate()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(item));when(mapper.count(any())).thenReturn(1L);
        for(int i=0;i<2;i++)mvc.perform(get("/api/developers")).andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.items[0].dev_id").value(1));
        verify(mapper,times(2)).list(argThat(q->q.today().equals(LocalDate.of(2026,10,23))&&q.order().equals("dev_nm ASC, dev_id ASC")&&q.useYn().equals("Y")));untouched();
    }
    @Test void andFiltersAndTotalShareOneQuery()throws Exception {
        mvc.perform(get("/api/developers").param("empType","OS").param("job","BE").param("q","K-0003").param("status","철수예정").param("page","1")) .andExpect(status().isOk());
        var c=org.mockito.ArgumentCaptor.forClass(DeveloperQuery.class);verify(mapper).list(c.capture());verify(mapper).count(c.getValue());
        assertThat(c.getValue().empType()).isEqualTo("OS");assertThat(c.getValue().job()).isEqualTo("BE");assertThat(c.getValue().q()).isEqualTo("K-0003");assertThat(c.getValue().offset()).isEqualTo(50);
    }
    @Test void disabledRetireeDetailHasPublicAccessAndLatestSalary()throws Exception {
        var disabled=new DeveloperListItem(1,"K-0001","개발자","자사","FT","정규직",null,null,"BE","백엔드","퇴사",null,0,BigDecimal.ZERO,false,null,null,"N",LocalDate.of(2026,10,1));
        when(mapper.summary(any())).thenReturn(disabled);when(mapper.latestSalary(1)).thenReturn(new LatestSalary("2026-12",4000000));
        for(int i=0;i<2;i++)mvc.perform(get("/api/developers/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.dev_id").value(1)).andExpect(jsonPath("$.data.use_yn").value("N")).andExpect(jsonPath("$.data.rsgn_dt").value("2026-10-01")).andExpect(jsonPath("$.data.latest_salary.month").value("2026-12"));untouched();
    }
    @Test void noSalaryIsExplicitNull()throws Exception {mvc.perform(get("/api/developers/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.latest_salary").value(org.hamcrest.Matchers.nullValue()));}
    @ParameterizedTest @CsvSource({"empType,XX","use_yn,X","status,오류","page,-1","size,30","sort,unknown","job,bad!","unknown,x"})
    void badListInputHasSingularFieldMessageAndRequestId(String field,String value)throws Exception {
        mvc.perform(get("/api/developers").param(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(header().exists("X-Request-Id"));untouched();
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","bad"})
    void badDetailIdHasContractError(String id)throws Exception {mvc.perform(get("/api/developers/"+id)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id")).andExpect(jsonPath("$.requestId").isNotEmpty());untouched();}
    @Test void missingDetailIs404()throws Exception {mvc.perform(get("/api/developers/99")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));}
    @Test void anonymousCreateAllocatesFinalNumberAndReadsCreatedIdentity()throws Exception {
        mvc.perform(post("/api/developers").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.dev_id").value(1)).andExpect(jsonPath("$.data.mng_no").value("K-0001")).andExpect(jsonPath("$.data.use_yn").value("Y"));
        verify(mapper).insert(argThat(w->w.id()==1&&w.no().equals("K-0001")&&w.actor()==null));
        verify(mapper).appendHistory(argThat(h->h.type().equals("INSERT")&&h.id().equals("1")&&h.column()==null));
        mvc.perform(get("/api/developers/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.dev_id").value(1));
    }
    @Test void createChildrenExcludeMainAndNormalizeSets()throws Exception {
        var b=body();b.put("sub_job",List.of("BE","UX","FE","FE"));b.put("skill",List.of("REACT","JAVA"));
        mvc.perform(post("/api/developers").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.dev_id").value(1));
        verify(mapper).insertJob(new DeveloperChild(1,"FE"));verify(mapper).insertJob(new DeveloperChild(1,"UX"));verify(mapper,times(2)).insertJob(any());verify(mapper,times(2)).insertSkill(any());
    }
    @ParameterizedTest @CsvSource({"dev_nm,51","tel_no,31","email_addr,101","rmrk_desc,501"})
    void invalidLengthsDoNotWrite(String field,int length)throws Exception {var b=body();b.put(field,"x".repeat(length));write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"dev_nm","emp_se_cd","main_job_cd"})
    void requiredFieldsDoNotWrite(String field)throws Exception {var b=body();b.remove(field);write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void createBusinessDuplicateIs409AndDoesNotWrite()throws Exception {
        when(mapper.duplicateNumber(anyString())).thenReturn(true);
        mvc.perform(post("/api/developers").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_MNG_NO"))
            .andExpect(jsonPath("$.message").value("이미 있는 관리번호입니다."));untouched();
    }
    @Test void createValidationMissingNameNeverWrites()throws Exception {
        var b=body();b.remove("dev_nm");
        mvc.perform(post("/api/developers").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("dev_nm"))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());untouched();
    }
    @Test void duplicateRaceTranslatedOutsideTransaction()throws Exception {when(mapper.insert(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));write("POST",body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_MNG_NO"));verify(mapper,never()).appendHistory(any());}
    @Test void gradeOnlyUpdateAndReadback()throws Exception {
        var b=body();b.put("grd_cd","S");
        mvc.perform(put("/api/developers/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(true));
        verify(mapper).update(argThat(w->w.changed().equals(Set.of("grd_cd"))));verify(mapper).appendHistory(argThat(h->h.type().equals("UPDATE")&&h.column().equals("grd_cd")&&h.before()==null&&h.after().equals("S")));
    }
    @Test void updateValidationOutsourcedRequiresPartnerAndDoesNotWrite()throws Exception {
        var b=body();b.put("emp_se_cd","OS");
        mvc.perform(put("/api/developers/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("vndr_ptnr_id"))
            .andExpect(jsonPath("$.message").value("외주는 협력사를 골라 주세요."));untouched();
    }
    @Test void inactivePartnerRejectedEvenWhenUnchanged()throws Exception {when(mapper.activePartner(2)).thenReturn(false);var b=body();b.put("emp_se_cd","OS");b.put("vndr_ptnr_id",2);write("PUT",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("vndr_ptnr_id"));untouched();}
    @Test void inactivePartRejected()throws Exception {when(mapper.activePart(2)).thenReturn(false);var b=body();b.put("bsn_part_id",2);write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_part_id"));untouched();}
    @Test void inactiveNewCodeRejected()throws Exception {when(mapper.activeCode(new DeveloperCode("GRADE","S"))).thenReturn(false);var b=body();b.put("grd_cd","S");write("PUT",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("grd_cd"));untouched();}
    @Test void unchangedInactiveCodeAndIgnoredManagementNumberAreZeroDml()throws Exception {when(mapper.activeCode(any())).thenReturn(false);var b=body();b.put("mng_no",Map.of("ignored",true));write("PUT",b).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(false)).andExpect(jsonPath("$.data.developer.mng_no").value("K-0001"));untouched();}
    @Test void unchangedCollectionOrderDoesNotReplaceChildren()throws Exception {when(mapper.subJobs(1)).thenReturn(List.of("FE","UX"));var b=body();b.put("sub_job",List.of("UX","FE"));write("PUT",b).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(false));untouched();}
    @Test void omittedOptionalScalarClearsAndOmittedDutyDefaultsDirect()throws Exception {
        when(mapper.row(1)).thenReturn(new DeveloperRow(1,"K-0001","개발자","FT","BE",null,null,"INDIRECT",null,null,null,"010",null,null,null,null));
        write("PUT",body()).andExpect(status().isOk());verify(mapper).update(argThat(w->w.changed().equals(Set.of("duty_se_cd","tel_no"))&&w.input().tel_no()==null&&w.input().duty_se_cd().equals("DIRECT")));
    }
    @Test void fullUpdateRequiresCollections()throws Exception {var b=body();b.remove("sub_job");write("PUT",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sub_job"));untouched();}
    @ParameterizedTest @ValueSource(strings={"slry_amt","rsgn_dt","use_yn"})
    void readonlyFieldsAreRejected(String field)throws Exception {var b=body();b.put(field,"x");write("PUT",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void missingUpdateIs404()throws Exception {when(mapper.row(1)).thenReturn(null);write("PUT",body()).andExpect(status().isNotFound());untouched();}
}
