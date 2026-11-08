package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import com.sicost.admin.*;
import com.sicost.common.api.*;
import com.sicost.common.health.HealthController;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers={AdminController.class,HealthController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,AdminService.class,CurrentUser.class,PermissionService.class,
    AuthorizationService.class,SecurityConfiguration.class,ProtectedApiTest.Time.class})
class ProtectedApiTest {
    @TestConfiguration static class Time { @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")); } }
    @Autowired MockMvc mvc;
    @MockBean AdminMapper mapper;
    @MockBean SecurityStateMapper security;
    @MockBean SessionService sessions;
    Cookie cookie;
    @BeforeEach void setup() {
        String raw=AuthenticationProof.randomId();cookie=new Cookie("JSESSIONID",raw);
        when(sessions.authenticate(raw)).thenReturn(new SecurityPrincipal(11L,"시험 관리자",List.of(1L),AuthenticationProof.digest(raw),false));
        when(sessions.expiresAt(any())).thenReturn(Instant.parse("2026-10-22T00:30:00Z"));
        when(security.account(11L)).thenReturn(new Account(11L,"시험 관리자",null,"ACTIVE",null,false));
        when(security.parts(11L)).thenReturn(List.of());when(security.projects(11L)).thenReturn(List.of());
        when(security.permissions(11L)).thenReturn(List.of(new Permission(1L,"시스템 관리자",DataScope.ALL,true,true,true,"UI-27","YYYYYYY",false,false)));
    }
    @Test void healthRemainsPublicJson() throws Exception { mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)); }
    @Test void anonymousAdminReadIs401WithoutDatabaseBusinessRead() throws Exception { mvc.perform(get("/api/admin/code-groups")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.requestId").isString());verifyNoInteractions(mapper); }
    @Test void anonymousWriteIs401NotCsrf403AndCannotSave() throws Exception { mvc.perform(post("/api/admin/code-groups").contentType("application/json").content("{\"cd_grp_id\":\"SECURITY_TEST\",\"cd_grp_nm\":\"시험\"}")).andExpect(status().isUnauthorized());verifyNoInteractions(mapper); }
    @Test void authenticatedListPreservesDtoAndDatabaseTotal() throws Exception { when(mapper.groups(any())).thenReturn(List.of(new AdminModels.CodeGroup("JOB","직무","BIZ",null,null,null,1,"Y",2)));when(mapper.groupCount()).thenReturn(36L);mvc.perform(get("/api/admin/code-groups").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(36)).andExpect(jsonPath("$.data.items[0].cd_grp_id").value("JOB")); }
    @Test void cookieAloneWithoutCsrfCannotWrite() throws Exception { mvc.perform(post("/api/admin/code-groups").cookie(cookie).contentType("application/json").content("{\"cd_grp_id\":\"SECURITY_TEST\",\"cd_grp_nm\":\"시험\"}")).andExpect(status().isForbidden());verifyNoInteractions(mapper); }
    @Test void authenticatedWriteUsesRealActorAndHistory() throws Exception { mvc.perform(post("/api/admin/code-groups").cookie(cookie).with(csrf()).contentType("application/json").content("{\"cd_grp_id\":\"SECURITY_TEST\",\"cd_grp_nm\":\"시험\"}")).andExpect(status().isOk());verify(mapper).createGroup(argThat(w->w.actor().equals(11L)));verify(mapper).appendHistory(argThat(w->w.actor().equals(11L))); }
    @Test void revocationInExistingTabRejectsWriteWithoutSideEffect() throws Exception { when(security.permissions(11L)).thenReturn(List.of());mvc.perform(post("/api/admin/code-groups").cookie(cookie).with(csrf()).contentType("application/json").content("{\"cd_grp_id\":\"SECURITY_TEST\",\"cd_grp_nm\":\"시험\"}")).andExpect(status().isForbidden());verifyNoInteractions(mapper); }
    @Test void restrictedSessionCannotReadBusinessOrIdentity() throws Exception { when(sessions.authenticate(cookie.getValue())).thenReturn(new SecurityPrincipal(11L,"시험 관리자",List.of(1L),"a".repeat(64),true));mvc.perform(get("/api/admin/code-groups").cookie(cookie)).andExpect(status().isForbidden());mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isForbidden());verifyNoInteractions(mapper); }
    @Test void requestIdentifierHeaderIsServerGenerated() throws Exception { mvc.perform(get("/api/health").header("X-Request-Id","untrusted-value")).andExpect(header().string("X-Request-Id",org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}"))); }
    @Test void foreignOriginIsNotAutomaticallyAllowed() throws Exception { mvc.perform(get("/api/admin/code-groups").cookie(cookie).header("Origin","https://untrusted.invalid")).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));verifyNoInteractions(mapper); }
    @Test void forwardedIdentityCannotAuthenticateAnonymousRequest() throws Exception { mvc.perform(get("/api/admin/code-groups").header("X-Forwarded-For","127.0.0.1").header("X-Forwarded-Proto","https")).andExpect(status().isUnauthorized());verifyNoInteractions(mapper); }
}
