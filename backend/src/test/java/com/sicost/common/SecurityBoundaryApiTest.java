package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.admin.*;
import com.sicost.common.api.*;
import com.sicost.common.health.HealthController;
import com.sicost.common.security.*;
import static com.sicost.common.security.AccessPolicyModels.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers={AdminController.class,HealthController.class,com.sicost.authentication.AuthenticationController.class})
@Import({ApplicationSecurityConfiguration.class,RequestIdFilter.class,ApiExceptionHandler.class,AdminService.class,
    CurrentUser.class,PermissionService.class,SecurityBoundaryApiTest.Time.class})
class SecurityBoundaryApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AdminMapper admin;
    @MockBean AccessPolicyMapper access;
    @MockBean com.sicost.authentication.AuthenticationService authentication;
    String proof;
    Account account;
    @BeforeEach void setup() {
        proof=AuthenticationProofs.randomSessionId();account=new Account(42L,"합성 사용자",null,"ACTIVE",null,false,LocalDateTime.of(2026,10,22,9,30));
        when(access.sessionOwner(any())).thenReturn(account);when(access.activeSession(any())).thenReturn(account);
        when(access.roles(42L)).thenReturn(List.of(new AccessPolicyModels.Role(1L,"시스템 관리자",DataScope.ALL,true,true,true,true)));
        when(access.grants(any())).thenReturn(List.of(new Grant(1L,DataScope.ALL,true,true,true,true,"시스템 관리자")));
        when(access.parts(42L)).thenReturn(List.of());when(access.projects(42L)).thenReturn(List.of());
    }
    Cookie session() { return new Cookie("JSESSIONID",proof); }
    @Test void csrfIsPublicJsonAndDoesNotReadBusinessData()throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.data.csrfToken").isNotEmpty()).andExpect(cookie().httpOnly("XSRF-TOKEN",true));
        verifyNoInteractions(admin,authentication);
    }
    @Test void identityWithoutSessionIs401BeforeServiceAccess()throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(authentication);
    }
    @Test void publicHealthIsJsonWithoutAuthentication()throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"));
        verifyNoInteractions(admin);
    }
    @Test void anonymousExistingBusinessApiIs401WithCommonEnvelope()throws Exception {
        mvc.perform(get("/api/admin/code-groups")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(admin);
    }
    @Test void anonymousWriteIs401AndDoesNotCreateGroup()throws Exception {
        mvc.perform(post("/api/admin/code-groups").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());verifyNoInteractions(admin);
    }
    @Test void activeSessionUsesExistingControllerAndDatabaseTotal()throws Exception {
        when(admin.groups(any())).thenReturn(List.of(new AdminModels.CodeGroup("SECURITY","검증","BIZ",null,null,null,1,"Y",1)));
        when(admin.groupCount()).thenReturn(1L);
        mvc.perform(get("/api/admin/code-groups").cookie(session())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].cd_grp_id").value("SECURITY"));
        verify(access).sessionOwner(argThat(q->q.sessionIdHash().equals(AuthenticationProofs.hash(proof))));
    }
    @Test void staleSessionHasNoBusinessRead()throws Exception {
        when(access.sessionOwner(any())).thenReturn(null);
        mvc.perform(get("/api/admin/code-groups").cookie(session())).andExpect(status().isUnauthorized());verifyNoInteractions(admin);
    }
    @Test void initialPasswordSessionCannotReadMenusOrPermissions()throws Exception {
        when(access.sessionOwner(any())).thenReturn(new Account(42L,"합성 사용자",null,"ACTIVE",null,true,account.expiresAt()));
        mvc.perform(get("/api/me/permissions").cookie(session())).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));verifyNoInteractions(admin);
    }
    @Test void menuDenialIs403EvenWhenCallerWouldIgnoreBoolean()throws Exception {
        when(access.grants(any())).thenReturn(List.of());
        mvc.perform(get("/api/admin/code-groups").cookie(session())).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));verifyNoInteractions(admin);
    }
    @Test void authenticatedWriteWithoutCsrfIs403AndDatabaseIsUntouched()throws Exception {
        mvc.perform(post("/api/admin/code-groups").cookie(session()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("cd_grp_id","SECURITY","cd_grp_nm","검증"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));verifyNoInteractions(admin);
    }
    @Test void csrfProtectedWriteRecordsAuthenticatedActor()throws Exception {
        var result=mvc.perform(get("/api/health")).andReturn();
        var token=(org.springframework.security.web.csrf.CsrfToken)result.getRequest().getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
        String value=token.getToken();
        Cookie csrfCookie=result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        mvc.perform(post("/api/admin/code-groups").cookie(session(),csrfCookie).header("X-CSRF-TOKEN",value)
            .contentType("application/json").content(json.writeValueAsString(Map.of("cd_grp_id","SECURITY","cd_grp_nm","검증"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(admin).createGroup(argThat(w->Long.valueOf(42L).equals(w.actor())));
        verify(admin).appendHistory(argThat(h->Long.valueOf(42L).equals(h.actor())));
    }
    @Test void publicLoginStillRequiresCsrf()throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));verifyNoInteractions(admin);
    }
    @Test void untrustedOriginCannotPerformAuthenticatedWrite()throws Exception {
        mvc.perform(post("/api/admin/code-groups").cookie(session()).header("Origin","https://untrusted.invalid")
            .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());verifyNoInteractions(admin);
    }
    @Test void duplicateCookiesCannotSelectAnIdentity()throws Exception {
        mvc.perform(get("/api/admin/code-groups").cookie(session(),session())).andExpect(status().isUnauthorized());verifyNoInteractions(admin);
    }
}
