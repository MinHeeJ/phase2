package com.sicost.contacts;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContactController.class)
@Import({ContactService.class,BusinessContactPolicy.class,AuthorizationService.class,PermissionService.class,CurrentUser.class,SecurityConfiguration.class,ApiExceptionHandler.class,RequestIdFilter.class,ContactApiTest.Time.class})
class ContactApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"),SessionService.SEOUL);} }
    @Autowired MockMvc mvc;
    @MockBean ContactMapper mapper;
    @MockBean SecurityStateMapper security;
    @MockBean SessionService sessions;
    @MockBean SecurityAuditService audit;
    Cookie cookie;
    Long actor,part,project,developer,partner;
    @BeforeEach void setup() {
        actor=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000000L)+1;part=actor+1;project=actor+2;developer=actor+3;partner=actor+4;
        String raw=AuthenticationProof.randomId();cookie=new Cookie("JSESSIONID",raw);
        when(sessions.authenticate(raw)).thenReturn(new SecurityPrincipal(actor,"담당자",List.of(4L),AuthenticationProof.digest(raw),false));
        when(sessions.expiresAt(any())).thenReturn(Instant.parse("2026-11-08T01:30:00Z"));when(security.account(actor)).thenReturn(new Account(actor,"담당자",null,"ACTIVE",null,false));
        when(security.parts(actor)).thenReturn(List.of(part));when(security.projects(actor)).thenReturn(List.of(project));
        role("영업 파트장",DataScope.PART);
        when(mapper.developerResources(developer)).thenReturn(List.of(new Resource(null,part,project,developer)));
        when(mapper.partnerResources(partner)).thenReturn(List.of(new Resource(null,part,project,null)));
        when(mapper.developer(developer)).thenReturn(new ContactMapper.Contact("allowed-phone","allowed@example.invalid",null,true));
        when(mapper.partner(partner)).thenReturn(new ContactMapper.Contact("business-phone","sales@example.invalid","finance@example.invalid",true));
    }
    void role(String role,DataScope scope) {when(security.permissions(actor)).thenReturn(List.of(new Permission(4L,role,scope,false,false,false,"UI-02","YNNNNNN",true,false),new Permission(4L,role,scope,false,false,false,"UI-15","YNNNNNN",true,false)));}
    @Test void developerOwnScopeReturnsOnlyContactAndAuditsPurpose()throws Exception {
        mvc.perform(get("/api/developers/{id}/contact",developer).param("purpose","STAFF_OPERATION").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("allowed-phone")).andExpect(jsonPath("$.data.email").value("allowed@example.invalid")).andExpect(jsonPath("$.data.userId").doesNotExist()).andExpect(jsonPath("$.data.mfa").doesNotExist());
        verify(audit).record(SecurityAuditService.Event.CONTACT_VIEW,"UI-02",developer,SecurityAuditService.Result.SUCCESS,SecurityAuditService.Purpose.STAFF_OPERATION);
    }
    @Test void partnerHasIndependentFinancialPurposeProjection()throws Exception {
        role("재무 담당",DataScope.ALL);
        mvc.perform(get("/api/partners/{id}/contact",partner).param("purpose","BILLING").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value("finance@example.invalid")).andExpect(jsonPath("$.data.loginId").doesNotExist());
        verify(audit).record(SecurityAuditService.Event.CONTACT_VIEW,"UI-15",partner,SecurityAuditService.Result.SUCCESS,SecurityAuditService.Purpose.BILLING);
    }
    @Test void partnerSalesOwnPartWorksButWrongPartIs404BeforeRawLookup()throws Exception {
        mvc.perform(get("/api/partners/"+partner+"/contact").param("purpose","CONTRACT_SALES").cookie(cookie)).andExpect(status().isOk());
        when(mapper.partnerResources(partner)).thenReturn(List.of(new Resource(null,part+100,project,null)));
        mvc.perform(get("/api/partners/"+partner+"/contact").param("purpose","CONTRACT_SALES").cookie(cookie)).andExpect(status().isNotFound());verify(mapper,times(1)).partner(partner);
    }
    @Test void pmOutsideProjectCannotReadRawContact()throws Exception {
        role("일반 사용자(PM)",DataScope.PROJECT);when(security.projects(actor)).thenReturn(List.of(project+100));
        mvc.perform(get("/api/developers/"+developer+"/contact").param("purpose","STAFF_OPERATION").cookie(cookie)).andExpect(status().isNotFound());verify(mapper,never()).developer(any());verifyNoInteractions(audit);
    }
    @Test void financeDeveloperAndExecutivePartnerAreForbidden()throws Exception {
        role("재무 담당",DataScope.ALL);mvc.perform(get("/api/developers/"+developer+"/contact").param("purpose","STAFF_OPERATION").cookie(cookie)).andExpect(status().isForbidden());
        role("경영진",DataScope.ALL);mvc.perform(get("/api/partners/"+partner+"/contact").param("purpose","CONTRACT_SALES").cookie(cookie)).andExpect(status().isForbidden());verifyNoInteractions(audit);
    }
    @Test void inactiveAndUnknownPurposeStayMasked()throws Exception {
        when(mapper.developer(developer)).thenReturn(new ContactMapper.Contact("private","private@example.invalid",null,false));
        mvc.perform(get("/api/developers/"+developer+"/contact").param("purpose","STAFF_OPERATION").cookie(cookie)).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/partners/"+partner+"/contact").param("purpose","ARBITRARY").cookie(cookie)).andExpect(status().isForbidden());verifyNoInteractions(audit);
    }
    @Test void auditFailureFailsClosedWithoutReturningContact()throws Exception {
        doThrow(new ApiException(503,"AUDIT_UNAVAILABLE","접근 기록을 저장할 수 없습니다.",null)).when(audit).record(any(),any(),any(),any(),any());
        mvc.perform(get("/api/developers/"+developer+"/contact").param("purpose","STAFF_OPERATION").cookie(cookie)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void anonymousCannotAccessEitherBusinessContact()throws Exception {
        mvc.perform(get("/api/developers/"+developer+"/contact").param("purpose","STAFF_OPERATION")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/partners/"+partner+"/contact").param("purpose","CONTRACT_SALES")).andExpect(status().isUnauthorized());verifyNoInteractions(mapper);
    }
}
