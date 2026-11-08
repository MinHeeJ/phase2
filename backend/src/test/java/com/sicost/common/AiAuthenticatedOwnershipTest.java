package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.ai.*;
import com.sicost.common.security.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class AiAuthenticatedOwnershipTest {
    final Instant now=Instant.parse("2026-10-31T15:00:00Z");
    final AiOwnerSession owners=new AiOwnerSession(Clock.fixed(now,ZoneOffset.UTC));
    final MockHttpServletRequest request=new MockHttpServletRequest();
    @AfterEach void clear() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void anonymousCannotCreateAnAiOwnerOrServletSession() { AuthorizationTest.status(()->owners.ownerHash(request),401);assertThat(request.getSession(false)).isNull(); }
    @Test void authenticatedUserHasStableOwnershipAcrossBrowserSessions() { AuthorizationTest.authenticate(11L);String owner=owners.ownerHash(request);assertThat(owners.ownerHash(new MockHttpServletRequest())).isEqualTo(owner);owners.requireOwner(request,owner);assertThat(request.getSession(false)).isNull(); }
    @Test void anotherUserCannotReadStoredConversationOrRecommendation() { AuthorizationTest.authenticate(11L);String owner=owners.ownerHash(request);AuthorizationTest.authenticate(12L);AuthorizationTest.status(()->owners.requireOwner(request,owner),404); }
    @Test void orphanAnonymousHistoryIsNotAssignedToCurrentUser() { AuthorizationTest.authenticate(11L);AuthorizationTest.status(()->owners.requireOwner(request,null),404);AuthorizationTest.status(()->owners.requireOwner(request,"a".repeat(64)),404); }
    @Test void productionUsesRealAuthorizationRatherThanDevelopmentOnlyDenial() { AuthorizationTest.authenticate(11L);var permissions=mock(PermissionService.class);when(permissions.can(AiActions.ASK)).thenReturn(true);var env=new MockEnvironment();env.setActiveProfiles("production");new AiAccessGuard(env,permissions).requireAction(AiActions.ASK);verify(permissions).can(AiActions.ASK); }
    @Test void inventedNaturalLanguagePermissionDoesNotReachPermissionService() { AuthorizationTest.authenticate(11L);var permissions=mock(PermissionService.class);AuthorizationTest.status(()->new AiAccessGuard(new MockEnvironment(),permissions).requireAction("administrator-all"),400);verifyNoInteractions(permissions); }
    @Test void expiryDuringModelWaitDoesNotRefreshOrReturnResult() { AuthorizationTest.authenticate(11L);String owner=owners.ownerHash(request);request.setAttribute(SessionService.class.getName()+".expiresAt",now);AuthorizationTest.status(()->owners.assertCurrent(request,owner),401); }
}
