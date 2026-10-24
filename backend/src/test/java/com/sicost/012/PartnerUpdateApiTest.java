package com.sicost.partners;

import static com.sicost.partners.PartnerModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerService.class,CurrentUser.class,PermissionService.class,PartnerUpdateApiTest.Time.class})
class PartnerUpdateApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerMapper mapper;
    PartnerDetail before=new PartnerDetail("1","C-001","BOTH","가 거래처","123-45-67890",null,null,"담당",null,null,"tax@company.co.kr","KB","123-456-7890","예금주","Y");
    @BeforeEach void setup() {
        when(mapper.lock(1)).thenReturn(before);
        when(mapper.detail(1)).thenReturn(before);
        when(mapper.activeCode(any())).thenReturn(true);
    }
    ObjectNode body(){return json.valueToTree(before.input());}
    ResultActions update(ObjectNode input)throws Exception {
        return mvc.perform(put("/api/partners/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)));
    }
    void unchanged(){verify(mapper,never()).update(any());verify(mapper,never()).appendHistory(any());}

    @Test void publicUpdateRequeriesAndAuditsOnlyTheChangedColumn()throws Exception {
        var input=body().put("ptnr_nm","변경한 상호");
        var after=new PartnerDetail("1","C-001","BOTH","변경한 상호",before.biz_reg_no(),null,null,"담당",null,null,before.txinv_email_addr(),"KB",before.acnt_no(),before.dpstr_nm(),"Y");
        when(mapper.detail(1)).thenReturn(after);
        update(input).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.ptnr_nm").value(after.ptnr_nm()));
        mvc.perform(get("/api/partners/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_nm").value(after.ptnr_nm()));
        verify(mapper).update(argThat(w->w.changed().equals(Set.of("ptnr_nm"))&&w.actor()==null));
        verify(mapper).appendHistory(new PartnerHistory("1","UPDATE","ptnr_nm",before.ptnr_nm(),after.ptnr_nm(),null));
        verify(mapper,never()).insert(any());
    }
    @Test void unchangedPutDoesNotIssueAnyUpdateOrHistory()throws Exception {
        update(body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.biz_reg_no").value(before.biz_reg_no()));
        unchanged();verify(mapper,never()).detail(anyLong());
    }
    @Test void businessNumberIsIgnoredEvenWhenNotAString()throws Exception {
        var input=body();input.putObject("biz_reg_no").put("ignored",true);
        update(input).andExpect(status().isOk()).andExpect(jsonPath("$.data.biz_reg_no").value(before.biz_reg_no()));unchanged();
    }
    @Test void omittedBusinessNumberIsAccepted()throws Exception {
        var input=body();input.remove("biz_reg_no");
        update(input).andExpect(status().isOk());unchanged();
    }
    @Test void accountChangeProducesExactlyOneSuffixOnlyAudit()throws Exception {
        update(body().put("acnt_no","987-654-3210")).andExpect(status().isOk());
        verify(mapper).update(argThat(w->w.changed().equals(Set.of("acnt_no"))));
        var capture=org.mockito.ArgumentCaptor.forClass(PartnerHistory.class);
        verify(mapper).appendHistory(capture.capture());
        assertThat(capture.getValue()).isEqualTo(new PartnerHistory("1","UPDATE","acnt_no","7890","3210",null));
    }
    @Test void changingToCustomerClearsAllPaymentFieldsWithoutAConflict()throws Exception {
        update(body().put("ptnr_se_cd","CUST")).andExpect(status().isOk());
        verify(mapper).update(argThat(w->w.changed().equals(Set.of("ptnr_se_cd","bank_cd","acnt_no","dpstr_nm"))
            &&w.input().bank_cd()==null&&w.input().acnt_no()==null&&w.input().dpstr_nm()==null));
        verify(mapper,times(4)).appendHistory(any());
        verify(mapper).appendHistory(new PartnerHistory("1","UPDATE","acnt_no","7890",null,null));
    }
    @Test void changingToVendorIgnoresInvalidHiddenEmail()throws Exception {
        update(body().put("ptnr_se_cd","VEND").put("txinv_email_addr","invalid")).andExpect(status().isOk());
        verify(mapper).update(argThat(w->w.input().txinv_email_addr()==null&&w.changed().equals(Set.of("ptnr_se_cd","txinv_email_addr"))));
    }
    @ParameterizedTest
    @CsvSource({"ptnr_se_cd,INVALID","ptnr_nm,''","txinv_email_addr,''","txinv_email_addr,invalid","bank_cd,''","acnt_no,1234567","acnt_no,1234567A","dpstr_nm,''"})
    void eachInvalidFieldRejectsBeforePersistence(String field,String value)throws Exception {
        update(body().put(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.requestId").isNotEmpty());unchanged();verify(mapper,never()).lock(anyLong());
    }
    @ParameterizedTest
    @CsvSource({"ptnr_nm,51","ceo_nm,21","chrg_nm,21","chrg_tel_no,21","rmrk_desc,501","txinv_email_addr,101","dpstr_nm,31","acnt_no,21"})
    void eachLengthLimitAppliesToUpdates(String field,int length)throws Exception {
        update(body().put(field,"x".repeat(length))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"bank_cd","indty_cd"})
    void newInactiveCodeIsRejected(String field)throws Exception {
        when(mapper.activeCode(any())).thenReturn(false);
        update(body().put(field,"INACTIVE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));unchanged();
    }
    @Test void unchangedInactiveCodesDoNotPreventAnUnrelatedEdit()throws Exception {
        when(mapper.activeCode(any())).thenReturn(false);
        update(body().put("chrg_nm","새 담당")).andExpect(status().isOk());
        verify(mapper,never()).activeCode(any());verify(mapper).update(argThat(w->w.changed().equals(Set.of("chrg_nm"))));
    }
    @Test void missingPartnerReturns404WithoutWrites()throws Exception {
        when(mapper.lock(1)).thenReturn(null);
        update(body()).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"id","ptnr_no","use_yn","upld_id","updated_by"})
    void serverOwnedFieldsCannotBeChanged(String field)throws Exception {
        update(body().put(field,"1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));unchanged();
    }
}
