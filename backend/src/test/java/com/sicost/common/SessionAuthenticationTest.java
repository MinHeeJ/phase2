package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class SessionAuthenticationTest {
    final SecurityStateMapper mapper=mock(SecurityStateMapper.class);
    final Instant start=Instant.parse("2026-10-31T14:30:00Z");
    final LocalDateTime seoul=LocalDateTime.of(2026,10,31,23,30);
    String raw;
    @BeforeEach void setup() {
        raw=AuthenticationProof.randomId();
        when(mapper.account(11L)).thenReturn(new Account(11L,"시험 사용자",null,"ACTIVE",null,false));
        when(mapper.roles(11L)).thenReturn(List.of(4L));
        when(mapper.session(AuthenticationProof.digest(raw))).thenReturn(new Session(AuthenticationProof.digest(raw),11L,seoul,seoul,seoul.plusMinutes(30),null,false));
    }
    SessionService at(long seconds) { return new SessionService(mapper,Clock.fixed(start.plusSeconds(seconds),ZoneOffset.UTC)); }
    @Test void validSessionReadsNoRawProofFromStorageAndDoesNotTouchActivity() { assertThat(at(1740).authenticate(raw).userId()).isEqualTo(11L);verify(mapper).session(AuthenticationProof.digest(raw));verify(mapper,never()).activity(any(),any()); }
    @Test void thirtyMinuteBoundaryExpiresEvenWithPolling() { at(1799).authenticate(raw);AuthorizationTest.status(()->at(1800).authenticate(raw),401);verify(mapper,never()).activity(any(),any()); }
    @Test void activityUsesServerSeoulTimeOnly() { when(mapper.activity(any(),any())).thenReturn(1);at(1740).activity(raw);verify(mapper).activity(AuthenticationProof.digest(raw),seoul.plusMinutes(29)); }
    @Test void expiredSessionCannotBeRevivedByActivity() { AuthorizationTest.status(()->at(1800).activity(raw),401);verify(mapper,never()).activity(any(),any()); }
    @Test void retirementAtSeoulMidnightRejectsExistingCookie() { when(mapper.account(11L)).thenReturn(new Account(11L,"시험 사용자",null,"ACTIVE",LocalDate.of(2026,11,1),false));when(mapper.session(any())).thenReturn(new Session(AuthenticationProof.digest(raw),11L,seoul,seoul.plusMinutes(29),seoul.plusMinutes(59),null,false));at(1799).authenticate(raw);AuthorizationTest.status(()->at(1800).authenticate(raw),401); }
    @ParameterizedTest @ValueSource(strings={"LOCKED","DISABLED"}) void accountStateIsFreshForEveryRequest(String status) { when(mapper.account(11L)).thenReturn(new Account(11L,"시험 사용자",null,status,null,false));AuthorizationTest.status(()->at(1).authenticate(raw),401); }
    @Test void revokedCookieCannotBeReused() { when(mapper.session(any())).thenReturn(new Session(AuthenticationProof.digest(raw),11L,seoul,seoul,seoul.plusMinutes(30),seoul.plusSeconds(1),false));AuthorizationTest.status(()->at(2).authenticate(raw),401); }
    @Test void missingCookieIsUnauthenticated() { AuthorizationTest.status(()->at(1).authenticate(null),401);verify(mapper,never()).session(any()); }
    @Test void loginRotatesAndPersistsOnlyDigest() {
        String issued=at(1).issue(11L,raw);assertThat(issued).isNotEqualTo(raw).matches("[A-Za-z0-9_-]{43}");
        var capture=ArgumentCaptor.forClass(SessionWrite.class);verify(mapper).insertSession(capture.capture());
        assertThat(capture.getValue().hash()).isEqualTo(AuthenticationProof.digest(issued)).doesNotContain(issued);
        verify(mapper).revoke(AuthenticationProof.digest(raw),seoul.plusSeconds(1));
    }
    @Test void passwordChangeOnlySessionCannotExtendBusinessActivity() { when(mapper.account(11L)).thenReturn(new Account(11L,"시험 사용자",null,"ACTIVE",null,true));assertThat(at(1).authenticate(raw).passwordChangeOnly()).isTrue();AuthorizationTest.status(()->at(1).activity(raw),403); }
    @Test void logoutAndPasswordChangeRevokeStoredHashes() { at(1).revoke(raw);at(1).revokeUser(11L);verify(mapper).revoke(AuthenticationProof.digest(raw),seoul.plusSeconds(1));verify(mapper).revokeUser(11L,seoul.plusSeconds(1)); }
    @Test void proofHashVerificationPreservesWhitespace() { String proof=" "+UUID.randomUUID()+" ! ";String hash=AuthenticationProof.hash(proof);assertThat(hash).isNotEqualTo(proof);assertThat(AuthenticationProof.matches(proof,hash)).isTrue();assertThat(AuthenticationProof.matches(proof.trim(),hash)).isFalse(); }
    @Test void sessionCookieUsesAllRequiredSecurityAttributesAndNoDomain() { var cookie=SessionService.cookie(raw);assertThat(cookie.isHttpOnly()).isTrue();assertThat(cookie.isSecure()).isTrue();assertThat(cookie.getSameSite()).isEqualTo("Lax");assertThat(cookie.getDomain()).isNull(); }
    @Test void otpIsSixDigitAndNeverStoredByGenerator() { assertThat(AuthenticationProof.otp()).matches("[0-9]{6}");verifyNoInteractions(mapper); }
    @Test void passwordRulesDoNotInventUppercaseRequirementOrTrim() { PasswordPolicy.validate(" test123! ");AuthorizationTest.status(()->PasswordPolicy.validate("abcdefgh"),400); }
}
