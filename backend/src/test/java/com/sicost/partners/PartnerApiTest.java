package com.sicost.partners;

import static com.sicost.partners.PartnerModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
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

@WebMvcTest(controllers=PartnerController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerService.class,CurrentUser.class,PermissionService.class,PartnerApiTest.Time.class})
class PartnerApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerMapper mapper;
    PartnerDetail both=new PartnerDetail("1","C-001","BOTH","기존 상호","123-45-67890",null,null,null,null,null,"tax@company.co.kr","KB","1234-5678-9012","예금주","Y");
    static Map<String,Object> input(String kind) {
        var b=new LinkedHashMap<String,Object>();b.put("ptnr_se_cd",kind);b.put("ptnr_nm","상호");b.put("biz_reg_no","123-45-67890");b.put("txinv_email_addr","tax@company.co.kr");b.put("bank_cd","KB");b.put("acnt_no","1234-5678-9012");b.put("dpstr_nm","예금주");return b;
    }
    @BeforeEach void setup() {
        when(mapper.activeCode(anyString(),anyString())).thenReturn(true);when(mapper.nextId()).thenReturn(1L);
        when(mapper.detail(1L)).thenReturn(both);when(mapper.forUpdate(1L)).thenReturn(both);
    }
    ResultActions write(String method,Map<String,Object> body)throws Exception {
        return mvc.perform((method.equals("POST")?post("/api/partners"):put("/api/partners/1")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    void untouched(){verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).appendHistory(any());}
    @Test @DisplayName("auth happy business side-effect: VEND and business number filters preserve business rows and history")
    void anonymousListHasPageAndSameCountFilters()throws Exception {
        var row=new PartnerListItem("1","C-001","BOTH","상호","123-45-67890",null,null,null,null,"tax@company.co.kr","Y","둘 다",null,"국민은행 …9012",2L,5);
        when(mapper.list(any())).thenReturn(List.of(row));when(mapper.count(any())).thenReturn(1L);
        mvc.perform(get("/api/partners").param("kind","VEND").param("q","1234567890")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].assignmentPeopleCount").value(5)).andExpect(jsonPath("$.data.items[0].projectCount").value(2)).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].acnt_no").doesNotExist()).andExpect(jsonPath("$.data.items[0].dpstr_nm").doesNotExist()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("1234-5678-9012"))));
        var capture=org.mockito.ArgumentCaptor.forClass(PartnerQuery.class);verify(mapper).list(capture.capture());verify(mapper).count(capture.getValue());assertThat(capture.getValue().kind()).isEqualTo("VEND");assertThat(capture.getValue().q()).isEqualTo("1234567890");assertThat(capture.getValue().today()).isEqualTo(LocalDate.of(2026,10,22));untouched();
    }
    @Test void emptyListIsNotAnError()throws Exception {when(mapper.list(any())).thenReturn(List.of());mvc.perform(get("/api/partners")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));untouched();}
    @ParameterizedTest @CsvSource({"kind,INVALID","useYn,X","page,0","size,30","sort,unknown","sort,ptnr_nm;DROP"})
    void invalidQuery(String field,String value)throws Exception {mvc.perform(get("/api/partners").param(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"CUST","VEND","BOTH"})
    @DisplayName("auth happy business side-effect: partner kind controls private detail keys without writes")
    void anonymousDetailOmitsIrrelevantKeys(String kind)throws Exception {
        var row=new PartnerDetail("1","C-001",kind,"상호","123-45-67890",null,null,null,null,null,kind.equals("VEND")?null:both.txinv_email_addr(),kind.equals("CUST")?null:"KB",kind.equals("CUST")?null:both.acnt_no(),kind.equals("CUST")?null:"예금주","N");when(mapper.detail(1L)).thenReturn(row);
        var result=mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N"));
        if(kind.equals("VEND")) result.andExpect(jsonPath("$.data.txinv_email_addr").doesNotExist());else result.andExpect(jsonPath("$.data.txinv_email_addr").value(both.txinv_email_addr()));
        if(kind.equals("CUST")) result.andExpect(jsonPath("$.data.bank_cd").doesNotExist()).andExpect(jsonPath("$.data.acnt_no").doesNotExist()).andExpect(jsonPath("$.data.dpstr_nm").doesNotExist());else result.andExpect(jsonPath("$.data.acnt_no").value(both.acnt_no()));
        mvc.perform(get("/api/partners/1")).andExpect(status().isOk());untouched();
    }
    @Test void missingDetailAndUpdateAre404()throws Exception {mvc.perform(get("/api/partners/2")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));mvc.perform(put("/api/partners/2").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("BOTH")))).andExpect(status().isNotFound());untouched();}
    @ParameterizedTest @ValueSource(strings={"0","-1","9223372036854775808","abc"}) void invalidId(String id)throws Exception {mvc.perform(get("/api/partners/"+id)).andExpect(status().isBadRequest());}
    @ParameterizedTest @ValueSource(strings={"CUST","VEND","BOTH"})
    @DisplayName("auth happy business side-effect: INSERT partner with ptnr_no and NULL actor audit")
    void anonymousCreateSetsCodeAndOneInsertAudit(String kind)throws Exception {
        mvc.perform(post("/api/partners").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(kind))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.ptnr_id").value("1")).andExpect(jsonPath("$.data.ptnr_no").value("C-001")).andExpect(jsonPath("$.data.use_yn").value("Y")).andExpect(header().exists("X-Request-Id"));
        verify(mapper).insert(argThat(w->w.id()==1L&&w.no().equals("C-001")&&w.actor()==null&&(!kind.equals("VEND")||w.values().get("txinv_email_addr")==null)&&(!kind.equals("CUST")||w.values().get("bank_cd")==null)));
        verify(mapper).appendHistory(argThat(h->h.id().equals("1")&&h.type().equals("INSERT")&&h.column()==null&&h.actor()==null));
    }
    @Test void codeDoesNotTruncateFourDigitId()throws Exception {when(mapper.nextId()).thenReturn(1000L);when(mapper.detail(1000L)).thenReturn(both);write("POST",input("CUST")).andExpect(status().isOk());verify(mapper).insert(argThat(w->w.no().equals("C-1000")));}
    @ParameterizedTest @CsvSource({"ptnr_se_cd,''","ptnr_nm,''","biz_reg_no,123456789","txinv_email_addr,''","txinv_email_addr,not-email","bank_cd,''","acnt_no,''","acnt_no,1234567","acnt_no,abcdefgh","dpstr_nm,''"})
    void invalidCreateFieldDoesNotWrite(String field,String value)throws Exception {
        var b=input("BOTH");b.put(field,value);
        mvc.perform(post("/api/partners").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty());untouched();
    }
    @ParameterizedTest @CsvSource({"ptnr_nm,51","ceo_nm,21","chrg_nm,21","chrg_tel_no,21","rmrk_desc,501","txinv_email_addr,101","dpstr_nm,31"})
    void lengthRules(String field,int length)throws Exception {var b=input("BOTH");b.put(field,"A".repeat(length));write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test @DisplayName("business: duplicate inactive business number rejects INSERT and data_chg_hist")
    void inactiveDuplicateIncludesNameAndDoesNotWrite()throws Exception {
        when(mapper.duplicateName(anyString())).thenReturn("중지 상호");
        mvc.perform(post("/api/partners").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("CUST"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_BIZ_NO")).andExpect(jsonPath("$.message").value("이미 등록된 사업자번호입니다. (중지 상호)"));untouched();
    }
    @Test void sameNameDifferentNumberIsAllowed()throws Exception {write("POST",input("VEND")).andExpect(status().isOk());verify(mapper).insert(any());}
    @Test void invalidBankIsRejected()throws Exception {when(mapper.activeCode("BANK","KB")).thenReturn(false);write("POST",input("VEND")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bank_cd"));untouched();}
    @Test void invalidIndustryIsRejected()throws Exception {var b=input("CUST");b.put("indty_cd","UNKNOWN");when(mapper.activeCode("INDUSTRY","UNKNOWN")).thenReturn(false);write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("indty_cd"));untouched();}
    @Test void nameOnlyUpdateAuditsOnlyName()throws Exception {var b=input("BOTH");b.put("ptnr_nm","새 상호");write("PUT",b).andExpect(status().isOk());verify(mapper).update(argThat(w->w.changed().equals(Set.of("ptnr_nm"))));verify(mapper).appendHistory(argThat(h->h.column().equals("ptnr_nm")&&h.before().equals("기존 상호")&&h.after().equals("새 상호")));}
    @Test void anonymousContactChangeIsAllowed()throws Exception {var b=input("BOTH");b.put("ptnr_nm",both.ptnr_nm());b.put("chrg_nm","새 담당자");write("PUT",b).andExpect(status().isOk());verify(mapper).update(argThat(w->w.changed().equals(Set.of("chrg_nm"))));}
    @Test @DisplayName("business side-effect: immutable biz_reg_no and no-op preserve updated_at and history")
    void updateIgnoresEvenMalformedBusinessNumberAndNoChangeDoesNotUpdate()throws Exception {
        var b=input("BOTH");b.put("ptnr_nm",both.ptnr_nm());b.put("biz_reg_no",List.of("ignored"));
        mvc.perform(put("/api/partners/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.biz_reg_no").value("123-45-67890"));untouched();
    }
    @Test void accountAuditContainsOnlyLastFour()throws Exception {var b=input("BOTH");b.put("ptnr_nm",both.ptnr_nm());b.put("acnt_no","9876-5432-1098");write("PUT",b).andExpect(status().isOk());verify(mapper).update(argThat(w->w.changed().equals(Set.of("acnt_no"))));verify(mapper).appendHistory(argThat(h->h.before().equals("9012")&&h.after().equals("1098")));}
    @ParameterizedTest @CsvSource({"txinv_email_addr,''","bank_cd,''","acnt_no,abcdefghi","dpstr_nm,''","ptnr_nm,''"})
    void invalidUpdatePreservesOriginal(String field,String value)throws Exception {var b=input("BOTH");b.put(field,value);write("PUT",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void roleRemovalClearsIrrelevantFieldsWithoutConflict()throws Exception {var b=input("VEND");b.put("ptnr_nm",both.ptnr_nm());write("PUT",b).andExpect(status().isOk());verify(mapper).update(argThat(w->w.changed().contains("txinv_email_addr")&&w.values().get("txinv_email_addr")==null));}
    @Test void laterSaveHasNoVersionGuard()throws Exception {var b=input("BOTH");b.put("ptnr_nm","첫 상호");write("PUT",b).andExpect(status().isOk());b.put("ptnr_nm","나중 상호");write("PUT",b).andExpect(status().isOk());verify(mapper).update(argThat(w->w.values().get("ptnr_nm").equals("나중 상호")));}
    @Test void productionMapperParsesAndBindsEveryDynamicBranch()throws Exception {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource();
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mappers/partners/PartnerMapper.xml"));factory.afterPropertiesSet();
        var config=factory.getObject().getConfiguration();
        for(String kind:List.of("","CUST","VEND","BOTH")) {
            var query=PartnerQuery.of(Map.of("kind",kind,"q","1234567890","useYn","Y"),LocalDate.of(2026,10,22));
            var list=config.getMappedStatement("com.sicost.partners.PartnerMapper.list").getBoundSql(query);
            var count=config.getMappedStatement("com.sicost.partners.PartnerMapper.count").getBoundSql(query);
            assertThat(list.getParameterMappings()).isNotEmpty();assertThat(count.getParameterMappings()).isNotEmpty();
        }
        var values=PartnerInput.parse(json.valueToTree(input("BOTH")),true);
        for(String field:values.keySet()) {
            if(field.equals("biz_reg_no"))continue;
            var write=new PartnerWrite(1L,"C-001",values,Set.of(field),null);
            assertThat(config.getMappedStatement("com.sicost.partners.PartnerMapper.update").getBoundSql(write).getParameterMappings()).hasSize(3);
        }
    }
}
