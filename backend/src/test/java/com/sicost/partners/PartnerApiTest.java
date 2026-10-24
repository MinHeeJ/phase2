package com.sicost.partners;

import static com.sicost.partners.PartnerModels.*;
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

@WebMvcTest(controllers=PartnerController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerService.class,CurrentUser.class,PermissionService.class,PartnerApiTest.Time.class})
class PartnerApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerMapper mapper;
    PartnerDetail both=new PartnerDetail("1","C-001","BOTH","가 거래처","123-45-67890",null,null,"담당",null,null,"tax@company.co.kr","KB","123-456-7890","예금주","Y");
    @BeforeEach void setup() {
        when(mapper.detail(1)).thenReturn(both);when(mapper.lock(1)).thenReturn(both);
        when(mapper.activeCode(any())).thenReturn(true);when(mapper.nextId()).thenReturn(1L);
    }
    Map<String,Object> body(String kind) {
        Map<String,Object> b=new LinkedHashMap<>();b.put("ptnr_se_cd",kind);b.put("ptnr_nm","신규 거래처");b.put("biz_reg_no","123-45-67890");b.put("txinv_email_addr","tax@company.co.kr");b.put("bank_cd","KB");b.put("acnt_no","123-456-7890");b.put("dpstr_nm","예금주");return b;
    }
    ResultActions write(String method,Map<String,Object> body)throws Exception {return mvc.perform((method.equals("POST")?post("/api/partners"):put("/api/partners/1")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    void untouched(){verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).appendHistory(any());}
    PartnerListItem item(String id,String name) {return new PartnerListItem(id,"C-00"+id,"BOTH",name,"123-45-67890",null,null,null,null,"tax@company.co.kr","Y","둘 다",null,"국민은행 …7890",2L,5);}
    @Test void publicListHasPageEnvelopeAndDefaultQuery()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(item("1","가"),item("2","나"),item("3","다")));when(mapper.count(any())).thenReturn(3L);
        mvc.perform(get("/api/partners")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(3)).andExpect(jsonPath("$.data.items[0].ptnr_nm").value("가")).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.total").value(3));
        verify(mapper).list(argThat(q->q.useYn().equals("Y")&&q.order().equals("ptnr_nm ASC, ptnr_id ASC")&&q.today().equals(LocalDate.of(2026,10,22))));untouched();
    }
    @Test void combinedFiltersAndCountUseSameQuery()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(item("1","거래처")));when(mapper.count(any())).thenReturn(1L);
        mvc.perform(get("/api/partners").param("kind","VEND").param("q","1234567890")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        var capture=org.mockito.ArgumentCaptor.forClass(PartnerQuery.class);verify(mapper).list(capture.capture());verify(mapper).count(capture.getValue());assertThat(capture.getValue().kind()).isEqualTo("VEND");assertThat(capture.getValue().q()).isEqualTo("1234567890");
    }
    @Test void emptyListIsNotAnError()throws Exception {mvc.perform(get("/api/partners")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.total").value(0));}
    @Test void listMasksAndNeverIncludesAccountKeys()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(item("1","거래처")));
        String result=mvc.perform(get("/api/partners")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].projectCount").value(2)).andExpect(jsonPath("$.data.items[0].assignmentPeopleCount").value(5)).andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("acnt_no","dpstr_nm","file_path_addr","123-456-7890");untouched();
    }
    @ParameterizedTest @CsvSource({"kind,INVALID","useYn,X","page,0","size,30","sort,unknown"})
    void invalidListInput(String field,String value)throws Exception {mvc.perform(get("/api/partners").param(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void allUseFilterIsPreserved()throws Exception {mvc.perform(get("/api/partners").param("useYn","")).andExpect(status().isOk());verify(mapper).list(argThat(q->q.useYn().isEmpty()));}
    @Test void vendInactiveDetailOmitsEmail()throws Exception {
        when(mapper.detail(1)).thenReturn(new PartnerDetail("1","C-001","VEND","협력사","123-45-67890",null,null,null,null,null,null,"KB","123-456-7890","예금주","N"));
        mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N")).andExpect(jsonPath("$.data.txinv_email_addr").doesNotExist());untouched();
    }
    @Test void publicCustomerDetailOmitsPaymentFields()throws Exception {
        when(mapper.detail(1)).thenReturn(new PartnerDetail("1","C-001","CUST","고객사","123-45-67890",null,null,null,null,null,"tax@company.co.kr",null,null,null,"Y"));
        mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bank_cd").doesNotExist()).andExpect(jsonPath("$.data.acnt_no").doesNotExist()).andExpect(jsonPath("$.data.dpstr_nm").doesNotExist());
    }
    @Test void missingDetailIs404()throws Exception {mvc.perform(get("/api/partners/99")).andExpect(status().isNotFound());untouched();}
    @Test void bothDetailIncludesFullApplicableFieldsAndReadsHaveNoWrites()throws Exception {for(int i=0;i<2;i++)mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.acnt_no").value("123-456-7890")).andExpect(jsonPath("$.data.txinv_email_addr").value("tax@company.co.kr"));untouched();}
    @Test void createAllocatesFinalCodeAndInsertAudit()throws Exception {
        mvc.perform(post("/api/partners").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body("CUST"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_no").value("C-001"))
            .andExpect(jsonPath("$.data.use_yn").value("Y"));
        verify(mapper).insert(argThat(w->w.id()==1&&w.no().equals("C-001")&&w.input().bank_cd()==null));
        verify(mapper).appendHistory(argThat(h->h.type().equals("INSERT")&&h.column()==null&&h.id().equals("1")&&h.actor()==null));
    }
    @ParameterizedTest @CsvSource({
        "CUST,ptnr_se_cd,'',구분을 골라 주세요.",
        "CUST,ptnr_nm,'',상호를 입력해 주세요.",
        "CUST,txinv_email_addr,'',고객사는 세금계산서 이메일이 꼭 필요합니다.",
        "BOTH,txinv_email_addr,'',고객사는 세금계산서 이메일이 꼭 필요합니다.",
        "VEND,bank_cd,'',은행을 골라 주세요.",
        "BOTH,bank_cd,'',은행을 골라 주세요.",
        "VEND,dpstr_nm,'',예금주를 입력해 주세요.",
        "BOTH,dpstr_nm,'',예금주를 입력해 주세요."
    })
    void createRequiredMessagesMatchContract(String kind,String field,String value,String message)throws Exception {
        var b=body(kind);b.put(field,value);
        write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").value(message));untouched();
    }
    @ParameterizedTest @ValueSource(strings={"ptnr_id","ptnr_no","use_yn","created_by","upld_id"})
    void createRejectsServerOwnedValues(String field)throws Exception {
        var b=body("CUST");b.put(field,"1");write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();
    }
    @Test void createRejectsInactiveIndustryWithoutAllocatingIdentity()throws Exception {
        var b=body("CUST");b.put("indty_cd","SI");when(mapper.activeCode(new PartnerCode("INDUSTRY","SI"))).thenReturn(false);
        write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("indty_cd"));verify(mapper,never()).nextId();untouched();
    }
    @Test void createTrimsEmailAndAccountAndAcceptsMaximumRemark()throws Exception {
        var b=body("BOTH");b.put("txinv_email_addr"," tax@company.co.kr ");b.put("acnt_no"," 123-456-7890 ");b.put("rmrk_desc","가".repeat(500));
        write("POST",b).andExpect(status().isOk());
        verify(mapper).insert(argThat(w->w.input().txinv_email_addr().equals("tax@company.co.kr")&&w.input().acnt_no().equals("123-456-7890")&&w.input().rmrk_desc().length()==500));
    }
    @Test void publicVendorCreateClearsUnrelatedEmail()throws Exception {write("POST",body("VEND")).andExpect(status().isOk());verify(mapper).insert(argThat(w->w.input().txinv_email_addr()==null&&w.input().bank_cd().equals("KB")));}
    @Test void thousandCodeIsNotTruncated()throws Exception {when(mapper.nextId()).thenReturn(1000L);when(mapper.detail(1000)).thenReturn(both);write("POST",body("CUST")).andExpect(status().isOk());verify(mapper).insert(argThat(w->w.no().equals("C-1000")));}
    @ParameterizedTest @CsvSource({"ptnr_se_cd,''","ptnr_nm,''","biz_reg_no,123456789","txinv_email_addr,''","txinv_email_addr,invalid","bank_cd,''","acnt_no,''","acnt_no,1234567","acnt_no,1234567A","dpstr_nm,''"})
    void invalidCreateFieldsDoNotWrite(String field,String value)throws Exception {var b=body("BOTH");b.put(field,value);write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @CsvSource({"ptnr_nm,51","ceo_nm,21","chrg_nm,21","chrg_tel_no,21","rmrk_desc,501","txinv_email_addr,101","dpstr_nm,31"})
    void lengthLimits(String field,int length)throws Exception {var b=body("BOTH");b.put(field,"x".repeat(length));write("POST",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void inactiveDuplicateReportsExistingName()throws Exception {when(mapper.duplicateName(anyString())).thenReturn("중지 거래처");write("POST",body("CUST")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_BIZ_NO")).andExpect(jsonPath("$.message").value("이미 등록된 사업자번호입니다. (중지 거래처)"));untouched();}
    @Test void duplicateRaceIsTranslatedAfterWriteReturns()throws Exception {when(mapper.insert(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("constraint"));when(mapper.duplicateName(anyString())).thenReturn(null,"경합 거래처");write("POST",body("CUST")).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("이미 등록된 사업자번호입니다. (경합 거래처)"));verify(mapper,never()).appendHistory(any());}
    @Test void inactiveBankRejected()throws Exception {when(mapper.activeCode(any())).thenReturn(false);write("POST",body("VEND")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bank_cd"));untouched();}
    @Test void nameUpdateOnlyWritesAndAuditsName()throws Exception {
        var b=json.convertValue(both.input(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});b.put("ptnr_nm","변경");
        PartnerDetail changed=new PartnerDetail("1",both.ptnr_no(),both.ptnr_se_cd(),"변경",both.biz_reg_no(),null,null,"담당",null,null,"tax@company.co.kr","KB","123-456-7890","예금주","Y");
        when(mapper.detail(1)).thenReturn(changed);
        mvc.perform(put("/api/partners/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_nm").value("변경"))
            .andExpect(jsonPath("$.data.ptnr_no").value(both.ptnr_no()));
        verify(mapper).update(argThat(w->w.changed().equals(Set.of("ptnr_nm"))));verify(mapper).appendHistory(argThat(h->h.column().equals("ptnr_nm")&&h.before().equals(both.ptnr_nm())&&h.after().equals("변경")));
    }
    @Test void publicContactUpdate()throws Exception {var b=json.convertValue(both.input(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});b.put("chrg_nm","새 담당");write("PUT",b).andExpect(status().isOk());verify(mapper).update(argThat(w->w.changed().equals(Set.of("chrg_nm"))));}
    @Test void updateMissingEmailKeepsOriginal()throws Exception {var b=body("BOTH");b.remove("txinv_email_addr");write("PUT",b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("txinv_email_addr"));untouched();}
    @Test void businessNumberIgnoredAndNoOpDoesNotUpdate()throws Exception {var b=json.convertValue(both.input(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});b.put("biz_reg_no","not validated");write("PUT",b).andExpect(status().isOk()).andExpect(jsonPath("$.data.biz_reg_no").value(both.biz_reg_no()));untouched();}
    @Test void accountAuditIsLastFourOnly()throws Exception {var b=json.convertValue(both.input(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});b.put("acnt_no","987-654-3210");write("PUT",b).andExpect(status().isOk());verify(mapper).appendHistory(argThat(h->h.column().equals("acnt_no")&&h.before().equals("7890")&&h.after().equals("3210")));}
    @Test void updateAllowsRoleRemovalAndClearsEmail()throws Exception {var b=json.convertValue(both.input(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});b.put("ptnr_se_cd","VEND");write("PUT",b).andExpect(status().isOk());verify(mapper).update(argThat(w->w.input().txinv_email_addr()==null&&w.changed().contains("ptnr_se_cd")));}
    @Test void missingUpdateIs404()throws Exception {when(mapper.lock(1)).thenReturn(null);write("PUT",body("CUST")).andExpect(status().isNotFound());untouched();}
    @Test void validationCreateBadBusinessNumberHasLiteralPathAndNoHistory()throws Exception {
        var input=body("CUST");input.put("biz_reg_no","123456789");
        mvc.perform(post("/api/partners").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("biz_reg_no"))
            .andExpect(jsonPath("$.message").isNotEmpty());untouched();
    }
    @Test void validationUpdateMissingEmailHasLiteralPathAndNoHistory()throws Exception {
        var input=body("BOTH");input.remove("txinv_email_addr");
        mvc.perform(put("/api/partners/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("txinv_email_addr"))
            .andExpect(jsonPath("$.message").isNotEmpty());untouched();
    }
}
