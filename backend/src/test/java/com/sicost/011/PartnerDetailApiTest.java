package com.sicost.partnerdetail;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.partners.*;
import com.sicost.partners.PartnerModels.PartnerDetail;
import com.sicost.partnersops.*;
import com.sicost.partnersops.PartnerLifecycleModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers={PartnerController.class,PartnerLifecycleController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerService.class,PartnerLifecycleService.class,
    ExcelExportService.class,CurrentUser.class,PermissionService.class,PartnerDetailApiTest.Time.class})
class PartnerDetailApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerMapper partners;
    @MockBean PartnerLifecycleMapper lifecycle;

    PartnerDetail detail(String kind,String use) {
        return new PartnerDetail("41","C-041",kind,"협력사 B","123-45-67890","대표자","SI","담당자","010-1234-5678","비고",
            kind.equals("VEND")?null:"tax@company.co.kr",kind.equals("CUST")?null:"004",
            kind.equals("CUST")?null:"001-23456789",kind.equals("CUST")?null:"협력사 B",use);
    }
    @BeforeEach void setup() {
        when(partners.detail(41)).thenReturn(detail("BOTH","Y"));
        when(lifecycle.detail("41")).thenReturn(new com.sicost.partnersops.PartnerLifecycleModels.PartnerDetail(
            "41","C-041","BOTH","협력사 B","123-45-67890",null,null,null,null,null,"tax@company.co.kr","004","001-23456789","협력사 B","Y"));
    }
    @AfterEach void readsNeverMutate() {
        verify(partners,never()).insert(any());verify(partners,never()).update(any());verify(partners,never()).appendHistory(any());
        verify(lifecycle,never()).changeState(any());verify(lifecycle,never()).audit(any());verify(lifecycle,never()).access(any());
    }
    @Test void inactiveVendorIsPublicAndOmitsEmail()throws Exception {
        when(partners.detail(41)).thenReturn(detail("VEND","N"));
        mvc.perform(get("/api/partners/41")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.ptnr_no").value("C-041"))
            .andExpect(jsonPath("$.data.use_yn").value("N")).andExpect(jsonPath("$.data.txinv_email_addr").doesNotExist())
            .andExpect(jsonPath("$.data.bank_cd").value("004")).andExpect(jsonPath("$.data.acnt_no").value("001-23456789"))
            .andExpect(jsonPath("$.data.dpstr_nm").value("협력사 B"));
    }
    @Test void publicCustomerOmitsAllPaymentKeys()throws Exception {
        when(partners.detail(41)).thenReturn(detail("CUST","Y"));
        mvc.perform(get("/api/partners/41")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.txinv_email_addr").value("tax@company.co.kr"))
            .andExpect(jsonPath("$.data.bank_cd").doesNotExist()).andExpect(jsonPath("$.data.acnt_no").doesNotExist())
            .andExpect(jsonPath("$.data.dpstr_nm").doesNotExist());
    }
    @Test void bothHasAllRegistrationFieldsAndRepeatedReadsAreSideEffectFree()throws Exception {
        for(int i=0;i<2;i++)mvc.perform(get("/api/partners/41")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ptnr_id").value("41")).andExpect(jsonPath("$.data.ptnr_se_cd").value("BOTH"))
            .andExpect(jsonPath("$.data.ptnr_nm").value("협력사 B")).andExpect(jsonPath("$.data.biz_reg_no").value("123-45-67890"))
            .andExpect(jsonPath("$.data.ceo_nm").value("대표자")).andExpect(jsonPath("$.data.indty_cd").value("SI"))
            .andExpect(jsonPath("$.data.chrg_nm").value("담당자")).andExpect(jsonPath("$.data.chrg_tel_no").value("010-1234-5678"))
            .andExpect(jsonPath("$.data.rmrk_desc").value("비고")).andExpect(jsonPath("$.data.txinv_email_addr").value("tax@company.co.kr"))
            .andExpect(jsonPath("$.data.acnt_no").value("001-23456789"));
        verify(partners,times(2)).detail(41);
    }
    @Test void missingDetailIs404()throws Exception {
        mvc.perform(get("/api/partners/999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
    }
    @Test void publicAssignmentsUseSeoulTodayAndDateAndNumericWireTypes()throws Exception {
        var query=new PartnerDateQuery("41",LocalDate.of(2026,10,23));
        when(lifecycle.assignments(query)).thenReturn(List.of(
            new PartnerAssignment("가 프로젝트","가 개발자","협력사 B","BE","백엔드",BigDecimal.ONE,LocalDate.of(2026,10,23)),
            new PartnerAssignment("나 프로젝트","나 개발자","자사","BE","백엔드",new BigDecimal("0.50"),LocalDate.of(2026,11,30))));
        mvc.perform(get("/api/partners/41/assignments")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].projectName").value("가 프로젝트"))
            .andExpect(jsonPath("$.data[0].developerName").value("가 개발자")).andExpect(jsonPath("$.data[0].affiliation").value("협력사 B"))
            .andExpect(jsonPath("$.data[1].affiliation").value("자사")).andExpect(jsonPath("$.data[1].assignmentRate").value(0.5))
            .andExpect(jsonPath("$.data[1].endDate").value("2026-11-30"));
        verify(lifecycle).assignments(query);
    }
    @Test void emptyAssignmentsAreAnArray()throws Exception {
        mvc.perform(get("/api/partners/41/assignments")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void missingAssignmentPartnerDoesNotRunTheQuery()throws Exception {
        mvc.perform(get("/api/partners/999/assignments")).andExpect(status().isNotFound());verify(lifecycle,never()).assignments(any());
    }
    @Test void publicHistoryPreservesOrderKoreanLabelsAndMaskedValues()throws Exception {
        when(lifecycle.history("41")).thenReturn(List.of(
            new PartnerChange(LocalDateTime.of(2026,10,23,10,30),"acnt_no","계좌번호","7890","3210","UPDATE","기본 사용자"),
            new PartnerChange(LocalDateTime.of(2026,10,22,9,0),"ptnr_nm","상호","이전","이후","UPDATE","기본 사용자")));
        mvc.perform(get("/api/partners/41/history")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].changedAt").value("2026-10-23T10:30:00"))
            .andExpect(jsonPath("$.data[0].itemName").value("계좌번호")).andExpect(jsonPath("$.data[0].before").value("7890"))
            .andExpect(jsonPath("$.data[0].after").value("3210")).andExpect(jsonPath("$.data[0].actorName").value("기본 사용자"))
            .andExpect(jsonPath("$.data[1].itemName").value("상호"));
    }
    @Test void emptyHistoryIsAnArray()throws Exception {
        mvc.perform(get("/api/partners/41/history")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void missingHistoryPartnerDoesNotRunTheQuery()throws Exception {
        mvc.perform(get("/api/partners/999/history")).andExpect(status().isNotFound());verify(lifecycle,never()).history(any());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","9223372036854775808"})
    void invalidLifecycleIdentifiersAre400(String id)throws Exception {
        for(String suffix:List.of("assignments","history"))mvc.perform(get("/api/partners/"+id+"/"+suffix))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
        verify(lifecycle,never()).assignments(any());verify(lifecycle,never()).history(any());
    }
}
