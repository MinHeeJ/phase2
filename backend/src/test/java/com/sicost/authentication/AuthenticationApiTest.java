package com.sicost.authentication;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(AuthenticationController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import({SecurityConfiguration.class,PermissionService.class,ApiExceptionHandler.class,RequestIdFilter.class})
class AuthenticationApiTest {
    @Autowired MockMvc mvc;
    @MockBean AuthenticationService service;
    @MockBean SessionService sessions;
    @MockBean NavigationService navigation;
    @MockBean AuthorizationService authorization;
    jakarta.servlet.http.Cookie authenticatedCookie() {
        String raw=AuthenticationProof.randomId();
        when(sessions.authenticate(raw)).thenReturn(new SecurityPrincipal(7L,"사용자",List.of(1L),AuthenticationProof.digest(raw),false));
        when(sessions.expiresAt(any())).thenReturn(java.time.Instant.parse("2099-01-01T00:00:00Z"));
        return new jakarta.servlet.http.Cookie("JSESSIONID",raw);
    }
    @Test void navigationReturnsServiceMenuTree()throws Exception {
        when(navigation.menus()).thenReturn(List.of());
        mvc.perform(get("/api/navigation").cookie(authenticatedCookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(navigation).menus();
    }
    @Test void permissionsReturnsActionMapAndScopes()throws Exception {
        when(navigation.permissions()).thenReturn(Map.of("permissions",Map.of("UI-02.read",true),"scopes",List.of("PART"),"menus",List.of()));
        mvc.perform(get("/api/me/permissions").cookie(authenticatedCookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.permissions['UI-02.read']").value(true)).andExpect(jsonPath("$.data.scopes[0]").value("PART"));
        verify(navigation).permissions();
    }
    @Test void mfaVerifyIssuesSessionOnlyAfterServiceSuccess()throws Exception {
        String challenge=AuthenticationProof.randomId(),session=AuthenticationProof.randomId();
        when(service.verify(challenge,"123456",null)).thenReturn(new AuthenticationService.LoginResult("AUTHENTICATED",session,null,null));
        mvc.perform(post("/api/auth/mfa/verify").cookie(new jakarta.servlet.http.Cookie("MFA_CHALLENGE",challenge)).with(csrf()).contentType("application/json").content("{\"code\":\"123456\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("AUTHENTICATED")).andExpect(cookie().httpOnly("JSESSIONID",true));
        verify(service).verify(challenge,"123456",null);
    }
    @Test void mfaResendReturnsExpiryWithoutExposingChallenge()throws Exception {
        String challenge=AuthenticationProof.randomId();
        when(service.resend(challenge)).thenReturn(java.time.Instant.parse("2026-11-08T01:05:00Z"));
        mvc.perform(post("/api/auth/mfa/resend").cookie(new jakarta.servlet.http.Cookie("MFA_CHALLENGE",challenge)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("MFA_REQUIRED")).andExpect(jsonPath("$.data.expiresInSeconds").value(300)).andExpect(jsonPath("$.data.challenge").doesNotExist());
        verify(service).resend(challenge);
    }
    @Test void forgotPasswordUsesNonEnumeratingResponse()throws Exception {
        mvc.perform(post("/api/auth/forgot-password").with(csrf()).contentType("application/json").content("{\"loginId\":\"unknown-account\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.message").value("등록된 계정이면 재설정 안내를 발송합니다."));
        verify(service).forgot(eq("unknown-account"),anyString());
    }
    @Test void resetForwardsProofWithoutReturningIt()throws Exception {
        mvc.perform(post("/api/auth/reset-password").with(csrf()).contentType("application/json").content("{\"token\":\"invalid-proof\",\"newPassword\":\"test-input\",\"confirmPassword\":\"test-input\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.token").doesNotExist());
        verify(service).reset("invalid-proof","test-input","test-input");
    }
    @Test void invalidResetIsErrorNotSuccessEnvelope()throws Exception {
        doThrow(ApiException.badRequest("잘못된 요청입니다.","token")).when(service).reset(any(),any(),any());
        mvc.perform(post("/api/auth/reset-password").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value("token"));
    }
    @Test void changePasswordUsesAuthenticatedUserId()throws Exception {
        mvc.perform(post("/api/auth/change-password").cookie(authenticatedCookie()).with(csrf()).contentType("application/json").content("{\"currentPassword\":\"old-input\",\"newPassword\":\"new-input\",\"confirmPassword\":\"new-input\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(service).change(7L,"old-input","new-input","new-input");
    }
    @Test void activityExtendsOnlyCurrentSession()throws Exception {
        var cookie=authenticatedCookie();
        mvc.perform(post("/api/auth/activity").cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(sessions).activity(cookie.getValue());
    }
    @Test void logoutRevokesCurrentSessionAndExpiresBothCookies()throws Exception {
        var cookie=authenticatedCookie();
        mvc.perform(post("/api/auth/logout").cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(cookie().maxAge("JSESSIONID",0)).andExpect(cookie().maxAge("MFA_CHALLENGE",0));
        verify(sessions).revoke(cookie.getValue());
    }
    @Test void csrfIsPublicButIdentityRequiresRealSession()throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN")).andExpect(jsonPath("$.data.csrfToken").isString());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void normalLoginSetsSecureCookieWithoutReturningSessionProof()throws Exception {
        String session=AuthenticationProof.randomId();when(service.login(any(),any(),any(),any())).thenReturn(new AuthenticationService.LoginResult("AUTHENTICATED",session,null,null));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"loginId\":\"account\",\"password\":\"input preserved\"}")).andExpect(status().isOk()).andExpect(cookie().httpOnly("JSESSIONID",true)).andExpect(cookie().secure("JSESSIONID",true)).andExpect(jsonPath("$.data.status").value("AUTHENTICATED")).andExpect(jsonPath("$.data.session").doesNotExist()).andExpect(jsonPath("$.data.password").doesNotExist());
    }
    @Test void mfaChallengeNeverIssuesNormalSession()throws Exception {
        when(service.login(any(),any(),any(),any())).thenReturn(new AuthenticationService.LoginResult("MFA_REQUIRED",null,AuthenticationProof.randomId(),java.time.Instant.parse("2026-11-08T01:05:00Z")));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"loginId\":\"account\",\"password\":\"private-input\"}")).andExpect(status().isOk()).andExpect(cookie().exists("MFA_CHALLENGE")).andExpect(cookie().doesNotExist("JSESSIONID")).andExpect(jsonPath("$.data.expiresInSeconds").value(300)).andExpect(jsonPath("$.data.challenge").doesNotExist());
    }
    @Test void mailFailureIs503AndNoSessionCookie()throws Exception {
        when(service.login(any(),any(),any(),any())).thenThrow(new ApiException(503,"MAIL_UNAVAILABLE","인증 메일을 보낼 수 없습니다.",null));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"loginId\":\"account\",\"password\":\"private-input\"}")).andExpect(status().isServiceUnavailable()).andExpect(cookie().doesNotExist("JSESSIONID")).andExpect(jsonPath("$.success").value(false));
    }
    @Test void runtimeJsonLoginDoesNotRequireCsrfPreflight()throws Exception {
        String session=AuthenticationProof.randomId();
        when(service.login(any(),any(),any(),any())).thenReturn(new AuthenticationService.LoginResult("AUTHENTICATED",session,null,null));
        mvc.perform(post("/api/auth/login").contentType("application/json;charset=UTF-8").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("AUTHENTICATED"))
            .andExpect(cookie().httpOnly("JSESSIONID",true)).andExpect(cookie().secure("JSESSIONID",true))
            .andExpect(jsonPath("$.data.session").doesNotExist());
    }
    @Test void browserLoginStillRequiresCsrfAndRejectsCrossOrigin()throws Exception {
        for(String origin:List.of("http://localhost","https://untrusted.invalid","null"))
            mvc.perform(post("/api/auth/login").header("Origin",origin).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        for(String site:List.of("same-origin","same-site","cross-site","none"))
            mvc.perform(post("/api/auth/login").header("Sec-Fetch-Site",site).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType("text/plain").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType("application/x-www-form-urlencoded"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void sameOriginBrowserLoginWithCsrfStillWorks()throws Exception {
        when(service.login(any(),any(),any(),any())).thenReturn(new AuthenticationService.LoginResult("AUTHENTICATED",AuthenticationProof.randomId(),null,null));
        mvc.perform(post("/api/auth/login").header("Origin","http://localhost").header("Sec-Fetch-Site","same-origin")
            .with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("AUTHENTICATED"));
    }
    @Test void otherPublicPostsStillRequireCsrf()throws Exception {
        for(String path:List.of("mfa/verify","mfa/resend","forgot-password","reset-password"))
            mvc.perform(post("/api/auth/"+path).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void wrongLoginUsesGenericFailureAndNoSecrets()throws Exception {
        when(service.login(any(),any(),any(),any())).thenReturn(null);
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"loginId\":\"absent\",\"password\":\"private-input\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED")).andExpect(jsonPath("$.message").value("아이디 또는 비밀번호를 확인하세요."));
    }
}
