package com.sicost.authentication;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static com.sicost.authentication.AuthenticationModels.*;

class AuthenticationApiTest {
    AuthenticationMapper mapper;AuthenticationMailDelivery mail;CurrentUser user;MockMvc mvc;
    ObjectMapper json=new ObjectMapper();Credentials account;String password;Clock clock;
    @BeforeEach void setup() {
        mapper=mock(AuthenticationMapper.class);mail=mock(AuthenticationMailDelivery.class);user=mock(CurrentUser.class);
        var transactions=mock(PlatformTransactionManager.class);when(transactions.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        var encoder=new BCryptPasswordEncoder(4);password=AuthenticationProofs.randomSessionId()+"!1a";
        clock=Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        account=new Credentials(741L,"contract.user",encoder.encode(password),"contract@example.invalid","ACTIVE",null,true,false,LocalDateTime.now(clock),0);
        when(mapper.loginAccount(anyString())).thenReturn(account);when(mapper.lockAccount(741L)).thenReturn(account);
        when(mapper.createProof(any(),any())).thenReturn(931L);when(mapper.outbox(any(),any(),anyString(),any())).thenReturn(851L);
        var service=new AuthenticationService(mapper,user,encoder,clock,transactions,mail);
        mvc=MockMvcBuilders.standaloneSetup(new AuthenticationController(service,new PermissionService(),new MockEnvironment()))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @Test void resendExpiredMfaIsValidationErrorWithoutMailOrSessionWrite()throws Exception {
        mvc.perform(post("/api/auth/mfa/resend"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(mail);verify(mapper,never()).insertSession(any());
    }
    @Test void cancelMfaRevokesOnlyTheBoundChallengeAndClearsCookie()throws Exception {
        when(mapper.preauth(anyString(),any())).thenReturn(new Proof(931L,741L,"unused","MFA",LocalDateTime.now(clock).plusMinutes(5)));
        mvc.perform(post("/api/auth/mfa/cancel").cookie(new Cookie("SICOST_PREAUTH",AuthenticationProofs.randomSessionId())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0")));
        verify(mapper).revokeProofs(741L);verify(mapper,never()).insertSession(any());verifyNoInteractions(mail);
    }
    @Test void changePasswordRejectsMismatchedConfirmationWithoutWrites()throws Exception {
        mvc.perform(post("/api/auth/change-password").contentType("application/json")
            .content(json.writeValueAsString(Map.of("currentPassword",password,"newPassword",password,"confirmPassword",password+"x"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("confirmPassword"));
        verify(mapper,never()).changePassword(any());verify(mapper,never()).revokeSessions(any(),any());
    }
    @Test void logoutRevokesAuthenticatedSessionAndClearsBothCookies()throws Exception {
        String proof=AuthenticationProofs.hash(AuthenticationProofs.randomSessionId());
        when(user.principal()).thenReturn(new AccessPolicyModels.SessionPrincipal(741L,proof));
        mvc.perform(post("/api/auth/logout")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(header().stringValues("Set-Cookie",org.hamcrest.Matchers.iterableWithSize(2)));
        verify(mapper).logout(eq(proof),any());verify(mapper).audit(argThat(a->a.type().equals("LOGOUT") && a.userId().equals(741L)));
    }
    @Test void activityValidatesInputBeforeAnySessionUpdate()throws Exception {
        mvc.perform(post("/api/auth/activity").contentType("application/json").content("{\"activityType\":\"INVALID\",\"screenId\":\"UI-02\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("activityType"));
        verify(mapper,never()).activity(anyString(),any());
    }
    @Test void activityUpdatesOnlyAuthenticatedSession()throws Exception {
        String proof=AuthenticationProofs.hash(AuthenticationProofs.randomSessionId());
        when(user.principal()).thenReturn(new AccessPolicyModels.SessionPrincipal(741L,proof));when(mapper.activity(eq(proof),any())).thenReturn(1);
        mvc.perform(post("/api/auth/activity").contentType("application/json").content("{\"activityType\":\"CLICK\",\"screenId\":\"UI-34\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).activity(eq(proof),any());verify(mapper,never()).insertSession(any());
    }
    @Test void meReturnsRolesAndIdentityButNeverCredentials()throws Exception {
        when(user.requireBusinessAccount()).thenReturn(new AccessPolicyModels.Account(741L,"검증 사용자",null,"ACTIVE",null,false,LocalDateTime.now(clock).plusMinutes(30)));
        when(user.roles()).thenReturn(List.of(new AccessPolicyModels.Role(837L,"검증 역할",DataScope.ALL,false,false,false,false)));
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(741))
            .andExpect(jsonPath("$.data.roles[0]").value("검증 역할")).andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        verify(mapper,never()).changePassword(any());
    }
    @Test void passwordAloneCreatesPreauthNotBusinessSessionAndOnlyHashIsPersisted()throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginId"," contract.user ","password",password))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("MFA_REQUIRED"))
            .andExpect(jsonPath("$.data.passwordChangeRequired").value(false)).andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        verify(mapper).loginAccount("contract.user");verify(mapper,never()).insertSession(any());
        verify(mapper).createProof(argThat(c->c.hash().equals(AuthenticationProofs.hash(c.raw())) && c.expiresAt().equals(c.now().plusMinutes(5))),argThat(s->s.matches("[0-9a-f]{64}")));
        verify(mapper).mailSent(eq(851L),any());
    }
    @Test void smtpFailureIs503AndDoesNotCreateSession()throws Exception {
        doThrow(new ApiException(503,"MAIL_UNAVAILABLE","메일을 보낼 수 없습니다.",null)).when(mail).send(anyString(),anyString(),anyString());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginId",account.loginId(),"password",password))))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MAIL_UNAVAILABLE"));
        verify(mapper,never()).insertSession(any());verify(mapper).mailFailed(851L);
    }
    @Test void wrongPasswordRecordsFailureButNeverSendsMail()throws Exception {
        String payload=json.writeValueAsString(Map.of("loginId",account.loginId(),"password",AuthenticationProofs.randomSessionId()));
        mvc.perform(post("/api/auth/login").contentType("application/json").content(payload))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        verify(mapper).failedPassword(account.userId());verifyNoInteractions(mail);verify(mapper,never()).insertSession(any());
    }
    @Test void expiredMfaDoesNotAuthenticate()throws Exception {
        mvc.perform(post("/api/auth/mfa/verify").cookie(new Cookie("SICOST_PREAUTH",AuthenticationProofs.randomSessionId())).contentType("application/json").content("{\"code\":\"123456\"}"))
            .andExpect(status().isBadRequest());verify(mapper,never()).insertSession(any());
    }
    @Test void fifthOtpFailureRevokesChallengeWithoutResettingBudget()throws Exception {
        String binding=AuthenticationProofs.randomSessionId();
        when(mapper.preauth(anyString(),any())).thenReturn(new Proof(931L,741L,AuthenticationProofs.hash(AuthenticationProofs.randomOtp()),"MFA",LocalDateTime.now(clock).plusMinutes(5)));
        when(mapper.lockAccount(741L)).thenReturn(new Credentials(account.userId(),account.loginId(),account.passwordHash(),account.email(),account.status(),null,true,false,account.passwordChangedAt(),4));
        mvc.perform(post("/api/auth/mfa/verify").cookie(new Cookie("SICOST_PREAUTH",binding)).contentType("application/json").content("{\"code\":\"not-a-code\"}"))
            .andExpect(status().isBadRequest());verify(mapper).failedOtp(741L);verify(mapper).revokeProofs(741L);verify(mapper,never()).insertSession(any());
    }
    @Test void validMfaCreatesRotatedHashedSession()throws Exception {
        String binding=AuthenticationProofs.randomSessionId(),otp=AuthenticationProofs.randomOtp();
        when(mapper.preauth(anyString(),any())).thenReturn(new Proof(931L,741L,AuthenticationProofs.hash(otp),"MFA",LocalDateTime.now(clock).plusMinutes(5)));
        when(mapper.consumeProof(eq(931L),any())).thenReturn(1);
        String payload=json.writeValueAsString(Map.of("code",otp));
        var result=mvc.perform(post("/api/auth/mfa/verify").cookie(new Cookie("SICOST_PREAUTH",binding)).contentType("application/json").content(payload))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("AUTHENTICATED")).andReturn();
        String header=result.getResponse().getHeaders("Set-Cookie").stream().filter(h->h.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(header).contains("HttpOnly","SameSite=Lax");verify(mapper).insertSession(argThat(s->s.hash().matches("[0-9a-f]{64}") && !s.hash().equals(binding)));
    }
    @Test void passwordMismatchIs400BeforePersistence()throws Exception {
        mvc.perform(post("/api/auth/reset-password").contentType("application/json").content(json.writeValueAsString(Map.of("token",AuthenticationProofs.randomSessionId(),"newPassword",password,"confirmPassword",password+"x"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("confirmPassword"));verify(mapper,never()).changePassword(any());
    }
    @Test void unknownForgotAccountHasGenericSuccessfulResponse()throws Exception {
        when(mapper.loginAccount(anyString())).thenReturn(null);
        mvc.perform(post("/api/auth/forgot-password").contentType("application/json").content("{\"loginId\":\"unknown\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.message").value("등록된 이메일로 안내를 보냈습니다."));verifyNoInteractions(mail);
    }
}
