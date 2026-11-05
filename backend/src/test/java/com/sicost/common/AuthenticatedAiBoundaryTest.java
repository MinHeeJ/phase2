package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.ai.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.security.AccessPolicyModels.Account;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthenticatedAiBoundaryTest {
    Clock clock=Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    @Test void authenticatedProductionActorUsesActionPolicyInsteadOfDevelopmentProfile() {
        var user=mock(CurrentUser.class);var permissions=mock(PermissionService.class);
        var env=new MockEnvironment();env.setActiveProfiles("production");
        when(user.requireBusinessAccount()).thenReturn(new Account(42L,"합성 사용자",null,"ACTIVE",null,false,LocalDateTime.of(2026,10,22,9,30)));
        var guard=new AiAccessGuard(env,user,permissions);guard.requireAction(AiActions.ASK);
        verify(permissions).can(AiActions.ASK);
    }
    @Test void anonymousRequestCannotAcquireAiOwnerOrCreateServletSession() {
        var request=new MockHttpServletRequest();var owners=new AiOwnerSession(clock,new CurrentUser());
        assertThatThrownBy(()->owners.ownerHash(request)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(401));
        assertThat(request.getSession(false)).isNull();
    }
    @Test void ownershipFollowsAuthenticatedUserAcrossSessionsNotHistoricalAnonymousHash() {
        var user=mock(CurrentUser.class);when(user.userId()).thenReturn(42L);
        var owners=new AiOwnerSession(clock,user);String hash=owners.ownerHash(new MockHttpServletRequest());
        owners.requireOwner(new MockHttpServletRequest(),hash);
        when(user.userId()).thenReturn(43L);
        assertThatThrownBy(()->owners.requireOwner(new MockHttpServletRequest(),hash))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(()->owners.requireOwner(new MockHttpServletRequest(),null)).isInstanceOf(ApiException.class);
    }
    @Test void ownerRecheckAfterPermissionRevocationDoesNotExtendIdleTime() {
        var user=mock(CurrentUser.class);when(user.userId()).thenReturn(42L).thenThrow(CurrentUser.unauthenticated());
        var owners=new AiOwnerSession(clock,user);var request=new MockHttpServletRequest();String hash=owners.ownerHash(request);
        assertThatThrownBy(()->owners.assertCurrent(request,hash)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(401));
        assertThat(request.getSession(false)).isNull();
    }
}
