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
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T16:00:00Z"),ZoneOffset.UTC);} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerMapper mapper;
    PartnerDetail before;
    @BeforeEach void setup() {
        before=row("BOTH","기존 상호","Y");
        when(mapper.detail(1L)).thenAnswer(i->before);when(mapper.original(1L)).thenAnswer(i->before);
        when(mapper.activeCode(anyString(),anyString())).thenReturn(true);when(mapper.nextId()).thenReturn(1L);
        doAnswer(i->{PartnerWrite w=i.getArgument(0);before=detail(w,before.biz_reg_no());return null;}).when(mapper).update(any());
        doAnswer(i->{PartnerWrite w=i.getArgument(0);before=detail(w,w.value().biz_reg_no());return null;}).when(mapper).insert(any());
    }
    PartnerDetail row(String kind,String name,String use) {return new PartnerDetail("1","C-001",kind,name,"123-45-67890",null,null,null,null,null,kind.equals("VEND")?null:"tax@company.co.kr",kind.equals("CUST")?null:"KB",kind.equals("CUST")?null:"123-456-7890",kind.equals("CUST")?null:"회사",use);}
    PartnerDetail detail(PartnerWrite w,String biz) {var v=w.value();return new PartnerDetail(Long.toString(w.id()),w.no(),v.ptnr_se_cd(),v.ptnr_nm(),biz,v.ceo_nm(),v.indty_cd(),v.chrg_nm(),v.chrg_tel_no(),v.rmrk_desc(),v.txinv_email_addr(),v.bank_cd(),v.acnt_no(),v.dpstr_nm(),"Y");}
    Map<String,Object> input(String kind) {
        var value=new LinkedHashMap<String,Object>();value.put("ptnr_se_cd",kind);value.put("ptnr_nm","기존 상호");value.put("biz_reg_no","123-45-67890");
        if(!kind.equals("VEND")) value.put("txinv_email_addr","tax@company.co.kr");
        if(!kind.equals("CUST")) {value.put("bank_cd","KB");value.put("acnt_no","123-456-7890");value.put("dpstr_nm","회사");}return value;
    }
    ResultActions write(String method,String path,Object body)throws Exception {return mvc.perform((method.equals("POST")?post(path):put(path)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    void untouched() {verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).appendHistory(any());}
    PartnerListItem item(String id,String name,String kind) {return new PartnerListItem(id,"C-00"+id,kind,name,"123-45-67890",null,null,null,null,null,"Y",kind,null,kind.equals("CUST")?null:"국민은행 …7890",kind.equals("VEND")?null:2L,5);}
    @Test void publicListDefaultsAndSafeRequiredNullableFields()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(item("1","가 회사","CUST"),item("2","나 회사","VEND"),item("3","다 회사","BOTH")));when(mapper.count(any())).thenReturn(3L);
        var response=mvc.perform(get("/api/partners")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(3)).andExpect(jsonPath("$.data.items[0].ptnr_nm").value("가 회사")).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.items[1].projectCount").isEmpty()).andExpect(jsonPath("$.data.items[0].paymentAccount").isEmpty()).andExpect(header().exists("X-Request-Id")).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("acnt_no","dpstr_nm","123-456-7890","file_path_addr").contains("\"projectCount\":null","\"paymentAccount\":null");
        verify(mapper).list(argThat(q->q.useYn().equals("Y")&&q.page()==1&&q.size()==50&&q.order().equals("ptnr_nm ASC NULLS LAST, ptnr_id ASC")&&q.today().equals(LocalDate.of(2026,10,23))));untouched();
    }
    @Test void filterAndCountShareSameQuery()throws Exception {
        when(mapper.list(any())).thenReturn(List.of(item("1","맞는 협력사","VEND")));when(mapper.count(any())).thenReturn(1L);
        mvc.perform(get("/api/partners").param("kind","VEND").param("q","1234567890").param("useYn","N").param("page","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        var captor=org.mockito.ArgumentCaptor.forClass(PartnerQuery.class);verify(mapper).list(captor.capture());verify(mapper).count(captor.getValue());assertThat(captor.getValue().kind()).isEqualTo("VEND");assertThat(captor.getValue().q()).isEqualTo("1234567890");assertThat(captor.getValue().offset()).isEqualTo(50);untouched();
    }
    @Test void emptyListIsArrayWithZeroTotal()throws Exception {when(mapper.list(any())).thenReturn(List.of());mvc.perform(get("/api/partners").param("q","없음")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.total").value(0));untouched();}
    @ParameterizedTest @CsvSource({"kind,INVALID","useYn,X","page,0","page,no","size,30","sort,unknown;asc","sort,ptnr_nm;DROP","sort,ptnr_nm,up"})
    void invalidQueryDoesNotReachMapper(String field,String value)throws Exception {mvc.perform(get("/api/partners").param(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));verify(mapper,never()).list(any());untouched();}
    @ParameterizedTest @ValueSource(strings={"ptnr_se_cd","ptnr_no","ptnr_nm","biz_reg_no","ceo_nm","indty_cd","chrg_nm","chrg_tel_no","txinv_email_addr","paymentAccount","projectCount","assignmentPeopleCount","use_yn"})
    void everyDisplayColumnIsServerSortable(String key)throws Exception {when(mapper.list(any())).thenReturn(List.of());mvc.perform(get("/api/partners").param("sort",key+",desc")).andExpect(status().isOk());verify(mapper).list(argThat(q->q.order().endsWith("DESC NULLS LAST, ptnr_id ASC")));}
    @Test void stoppedVendorDetailOmitsEmailAndReadsWithoutWrites()throws Exception {before=row("VEND","중지 협력사","N");mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N")).andExpect(jsonPath("$.data.txinv_email_addr").doesNotExist()).andExpect(jsonPath("$.data.acnt_no").value("123-456-7890"));mvc.perform(get("/api/partners/1")).andExpect(status().isOk());untouched();}
    @Test void publicCustomerDetailOmitsAllPaymentKeys()throws Exception {before=row("CUST","고객사","Y");mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bank_cd").doesNotExist()).andExpect(jsonPath("$.data.acnt_no").doesNotExist()).andExpect(jsonPath("$.data.dpstr_nm").doesNotExist()).andExpect(jsonPath("$.data.txinv_email_addr").value("tax@company.co.kr"));untouched();}
    @Test void bothDetailContainsBothSections()throws Exception {mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bank_cd").value("KB")).andExpect(jsonPath("$.data.dpstr_nm").value("회사")).andExpect(jsonPath("$.data.txinv_email_addr").value("tax@company.co.kr"));untouched();}
    @ParameterizedTest @ValueSource(strings={"2","0","abc","9223372036854775808"})
    void absentDetailIs404AndUnchanged(String id)throws Exception {mvc.perform(get("/api/partners/"+id)).andExpect(status().isNotFound());untouched();}
    @Test void publicCreateCustomerReservesIdAndCommitsSingleInsertAudit()throws Exception {
        mvc.perform(post("/api/partners").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("CUST"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_no").value("C-001")).andExpect(jsonPath("$.data.use_yn").value("Y"));
        var order=inOrder(mapper);order.verify(mapper).nextId();order.verify(mapper).insert(argThat(w->w.id()==1&&w.no().equals("C-001")&&w.actor()==null));order.verify(mapper).appendHistory(argThat(h->h.id().equals("1")&&h.type().equals("INSERT")&&h.column()==null&&h.actor()==null));
    }
    @Test void publicVendorCreateThenDetail()throws Exception {write("POST","/api/partners",input("VEND")).andExpect(status().isOk()).andExpect(jsonPath("$.data.acnt_no").value("123-456-7890"));mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.txinv_email_addr").doesNotExist());}
    @Test void codeDoesNotTruncateAtThousand() {assertThat(PartnerService.code(1000)).isEqualTo("C-1000");assertThat(PartnerService.code(1)).isEqualTo("C-001");}
    @ParameterizedTest @CsvSource({"ptnr_se_cd,''","ptnr_nm,''","biz_reg_no,123456789","txinv_email_addr,''","txinv_email_addr,invalid","bank_cd,''","acnt_no,''","dpstr_nm,''","acnt_no,1234567","acnt_no,abcdefgh"})
    void createFieldValidationHasNoSideEffects(String field,String value)throws Exception {var body=input("BOTH");body.put(field,value);write("POST","/api/partners",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @CsvSource({"ptnr_nm,51","ceo_nm,21","chrg_nm,21","chrg_tel_no,21","rmrk_desc,501","txinv_email_addr,101","dpstr_nm,31","acnt_no,21"})
    void lengthsRejectedForCreateAndUpdate(String field,int length)throws Exception {var body=input("BOTH");body.put(field,"A".repeat(length));write("POST","/api/partners",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));write("PUT","/api/partners/1",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"ptnr_id","ptnr_no","use_yn","upld_id","created_by"})
    void serverOwnedFieldsRejected(String field)throws Exception {var body=input("CUST");body.put(field,"1");write("POST","/api/partners",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void stoppedBusinessNumberConflictNamesExistingPartner()throws Exception {when(mapper.duplicateName("123-45-67890")).thenReturn("중지 상호");write("POST","/api/partners",input("CUST")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_BIZ_NO")).andExpect(jsonPath("$.message").value("이미 등록된 사업자번호입니다. (중지 상호)"));untouched();}
    @Test void uniqueRaceIsTranslatedAfterTransactionBoundary()throws Exception {doThrow(new org.springframework.dao.DuplicateKeyException("conflict")).when(mapper).insert(any());when(mapper.duplicateName(anyString())).thenReturn(null,"경쟁 상호");write("POST","/api/partners",input("CUST")).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("이미 등록된 사업자번호입니다. (경쟁 상호)"));verify(mapper,never()).appendHistory(any());}
    @Test void irrelevantEmailIsDiscardedWithoutValidation()throws Exception {var body=input("VEND");body.put("txinv_email_addr","not email");write("POST","/api/partners",body).andExpect(status().isOk());verify(mapper).insert(argThat(w->w.value().txinv_email_addr()==null));}
    @Test void irrelevantCustomerPaymentIsDiscarded()throws Exception {var body=input("CUST");body.put("acnt_no","bad");body.put("bank_cd","unknown");write("POST","/api/partners",body).andExpect(status().isOk());verify(mapper).insert(argThat(w->w.value().acnt_no()==null&&w.value().bank_cd()==null));}
    @Test void invalidBankCodeRejected()throws Exception {when(mapper.activeCode("BANK","KB")).thenReturn(false);write("POST","/api/partners",input("VEND")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bank_cd"));untouched();}
    @Test void invalidIndustryCodeRejected()throws Exception {when(mapper.activeCode("INDUSTRY","invalid")).thenReturn(false);var body=input("CUST");body.put("indty_cd","invalid");write("POST","/api/partners",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("indty_cd"));untouched();}
    @Test void publicNameUpdateChangesOnlyNameAndSingleAudit()throws Exception {
        var body=input("BOTH");body.put("ptnr_nm","새 상호");
        mvc.perform(put("/api/partners/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_nm").value("새 상호"))
            .andExpect(jsonPath("$.data.biz_reg_no").value("123-45-67890"));
        verify(mapper).update(argThat(w->w.changed().equals(Set.of("ptnr_nm"))));
        verify(mapper).appendHistory(argThat(h->h.column().equals("ptnr_nm")&&h.before().equals("기존 상호")&&h.after().equals("새 상호")));
    }
    @Test void publicContactUpdateCanBeReadBack()throws Exception {var body=input("BOTH");body.put("chrg_nm","담당자");write("PUT","/api/partners/1",body).andExpect(status().isOk());mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.chrg_nm").value("담당자"));}
    @ParameterizedTest @ValueSource(strings={"txinv_email_addr","bank_cd","acnt_no","dpstr_nm"})
    void updateConditionalRequiredFieldDoesNotChangeOriginal(String field)throws Exception {
        var body=input("BOTH");body.remove(field);
        mvc.perform(put("/api/partners/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(before).isEqualTo(row("BOTH","기존 상호","Y"));untouched();
    }
    @Test void ignoredBusinessNumberAndNoOpNeverIssueUpdate()throws Exception {var body=input("BOTH");body.put("biz_reg_no","invalid new number");write("PUT","/api/partners/1",body).andExpect(status().isOk()).andExpect(jsonPath("$.data.biz_reg_no").value("123-45-67890"));untouched();}
    @Test void accountAuditContainsOnlyLastFourDigits()throws Exception {var body=input("BOTH");body.put("acnt_no","987-654-3210");write("PUT","/api/partners/1",body).andExpect(status().isOk());verify(mapper).update(argThat(w->w.changed().equals(Set.of("acnt_no"))));verify(mapper).appendHistory(argThat(h->h.column().equals("acnt_no")&&h.before().equals("7890")&&h.after().equals("3210")));}
    @Test void sequentialWritesAreLastWriteWins()throws Exception {var body=input("BOTH");body.put("ptnr_nm","먼저 상호");write("PUT","/api/partners/1",body).andExpect(status().isOk());body.put("ptnr_nm","나중 상호");write("PUT","/api/partners/1",body).andExpect(status().isOk());mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_nm").value("나중 상호"));}
    @Test void updateMissingIdIs404()throws Exception {
        mvc.perform(put("/api/partners/2").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("BOTH"))))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));untouched();
    }
    @Test void roleRemovalIsAllowedAndNormalizesRemovedFields()throws Exception {write("PUT","/api/partners/1",input("VEND")).andExpect(status().isOk());verify(mapper).update(argThat(w->w.changed().containsAll(Set.of("ptnr_se_cd","txinv_email_addr"))&&w.value().txinv_email_addr()==null));}
}
