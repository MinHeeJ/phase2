package com.sicost.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.authentication.AuthenticationService.*;

class AuthenticationApiTest {
    AuthenticationMapper mapper;
    PlatformTransactionManager transactions;
    MockMvc mvc;
    Map<String,Object> account;
    @BeforeEach void setup() {
        mapper=mock(AuthenticationMapper.class);transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        var factory=new StaticListableBeanFactory();
        var service=new AuthenticationService(mapper,transactions,Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"),SecurityTime.SEOUL),factory.getBeanProvider(JavaMailSender.class));
        mvc=MockMvcBuilders.standaloneSetup(new AuthenticationController(service)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        account=values("id",101L,"name","시험 사용자","passwordHash",new BCryptPasswordEncoder(4).encode("Trial!1234"),"status","ACTIVE","mfaRequired",false,"passwordChangeRequired",false);
        when(mapper.account(any())).thenReturn(account);
    }
    @Test void loginTrimsOnlyIdAndPersistsOnlyProofHashes() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"loginId\":\"  test.person  \",\"password\":\"Trial!1234\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("AUTHENTICATED")).andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andExpect(jsonPath("$.data.token").doesNotExist()).andExpect(header().exists("Set-Cookie"));
        verify(mapper).account(argThat(q -> "test.person".equals(q.get("loginId"))));
        verify(mapper).insertSession(argThat(q -> q.get("sessionHash").toString().matches("[0-9a-f]{64}") && q.get("csrfHash").toString().matches("[0-9a-f]{64}")));
        verify(mapper).complete(any());
    }
    @Test void wrongPasswordCommitsFailureBeforeUniform401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"loginId\":\"test.person\",\"password\":\" Trial!1234 \"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("passwordHash"))));
        verify(mapper).failed(any());verify(transactions,atLeastOnce()).commit(any());verify(mapper,never()).insertSession(any());
    }
    @Test void administratorMailFailureNeverIssuesSession() throws Exception {
        account.put("mfaRequired",true);
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"loginId\":\"test.person\",\"password\":\"Trial!1234\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MFA_UNAVAILABLE"));
        verify(mapper,never()).insertSession(any());verify(mapper,never()).complete(any());
    }
    @Test void lockedAndMissingAccountsHaveSameResponse() throws Exception {
        account.put("status","LOCKED");
        var locked=mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"loginId\":\"locked\",\"password\":\"Trial!1234\"}")).andExpect(status().isUnauthorized()).andReturn();
        when(mapper.account(any())).thenReturn(null);
        var missing=mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"loginId\":\"missing\",\"password\":\"Trial!1234\"}")).andExpect(status().isUnauthorized()).andReturn();
        var json=new ObjectMapper();org.assertj.core.api.Assertions.assertThat(json.readTree(locked.getResponse().getContentAsString()).path("message")).isEqualTo(json.readTree(missing.getResponse().getContentAsString()).path("message"));
        verify(mapper,never()).insertSession(any());
    }
    @Test void invalidPasswordConfirmationDoesNotConsumeReset() throws Exception {
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content("{\"token\":\"invalid\",\"newPassword\":\"Trial!1234\",\"confirmPassword\":\"different\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("newPassword"));
        verify(mapper,never()).consume(any());verify(mapper,never()).password(any());
    }
    @Test void csrfTransportAndAnonymousMeAreDistinct() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN")).andExpect(jsonPath("$.data.csrfToken").isString());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
    }
    @Test void activityRequiresValidInputAndUpdatesOnlyCurrentSession() throws Exception {
        String proof=AuthenticationProofs.randomId();
        when(mapper.session(any())).thenReturn(values("id",101L,"sessionHash",AuthenticationProofs.hash(proof)));
        mvc.perform(post("/api/auth/activity").cookie(new jakarta.servlet.http.Cookie("JSESSIONID",proof))
            .contentType("application/json").content("{\"activityType\":\"CLICK\",\"screenId\":\"UI-23\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).activity(argThat(q -> AuthenticationProofs.hash(proof).equals(q.get("sessionHash"))));
        clearInvocations(mapper);
        mvc.perform(post("/api/auth/activity").contentType("application/json").content("{\"activityType\":\"POLL\",\"screenId\":\"UI-23\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("activityType"));
        verify(mapper,never()).activity(any());
    }
    @Test void changePasswordRejectsAnonymousAndInvalidConfirmationWithoutWrite() throws Exception {
        mvc.perform(post("/api/auth/change-password").contentType("application/json").content("{\"newPassword\":\"Trial!1234\",\"confirmPassword\":\"Trial!1234\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/auth/change-password").contentType("application/json").content("{\"newPassword\":\"short\",\"confirmPassword\":\"short\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("newPassword"));
        verify(mapper,never()).password(any());verify(mapper,never()).revoke(any());
    }
    @Test void forgotPasswordUsesUniformEnvelopeAndValidatesId() throws Exception {
        when(mapper.account(any())).thenReturn(null);
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"loginId\":\"missing\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.token").doesNotExist());
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("loginId"));
        verify(mapper,never()).insertChallenge(any());verify(mapper,never()).password(any());
    }
    @Test void resendWithoutPreauthenticationCannotCreateChallengeOrSession() throws Exception {
        mvc.perform(post("/api/auth/mfa/resend")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        verifyNoInteractions(mapper);
    }
    @Test void cancellationInvalidatesOnlyExistingMfaChallengeAndClearsCookie() throws Exception {
        String proof=AuthenticationProofs.randomId();
        when(mapper.challenge(any())).thenReturn(values("id",101L));
        mvc.perform(post("/api/auth/mfa/cancel").cookie(new jakarta.servlet.http.Cookie("PREAUTH",proof)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0")));
        verify(mapper).invalidateTokens(argThat(q -> Long.valueOf(101).equals(q.get("id")) && "MFA".equals(q.get("type"))));
        verify(mapper,never()).insertSession(any());
    }
    @Test void rateLimitPreventsAccountLookupAndLeaksNoCounter() throws Exception {
        when(mapper.attempts(any())).thenReturn(5L);
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"loginId\":\"test.person\",\"password\":\"Trial!1234\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(jsonPath("$.count").doesNotExist());
        verify(mapper,never()).account(any());
    }
}
