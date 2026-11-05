package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import static com.sicost.common.security.AccessPolicyModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.env.MockEnvironment;

class AccessPolicyTest {
    AccessPolicyMapper mapper;
    CurrentUser user;
    PermissionService permissions;
    Clock clock=Clock.fixed(Instant.parse("2026-10-22T15:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void setup() {
        mapper=mock(AccessPolicyMapper.class);user=new CurrentUser(mapper,clock);permissions=new PermissionService(user,mapper);
        var principal=new SessionPrincipal(42L,AuthenticationProofs.hash(AuthenticationProofs.randomSessionId()));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
        when(mapper.activeSession(any())).thenReturn(account(false));
        when(mapper.parts(42L)).thenReturn(List.of(10L));when(mapper.projects(42L)).thenReturn(List.of(100L));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    Account account(boolean restricted) { return new Account(42L,"시험 사용자",7L,"ACTIVE",null,restricted,LocalDateTime.of(2026,10,23,0,30)); }
    Grant grant(long id,DataScope scope,boolean sensitive,boolean admin) {
        return new Grant(id,scope,sensitive,false,admin,true,"역할");
    }
    void grants(Grant... grants) { when(mapper.grants(any())).thenReturn(List.of(grants)); }
    void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,int status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(status));
    }
    @Test void missingAuthenticationIs401AndNeverReadsPermissions() {
        SecurityContextHolder.clearContext();status(()->permissions.can("UI-16.read"),401);verify(mapper,never()).grants(any());
    }
    @Test void expiredRevokedLockedDisabledOrResignedSessionIs401() {
        when(mapper.activeSession(any())).thenReturn(null);status(user::userId,401);verify(mapper,never()).roles(any());
    }
    @Test void passwordChangeSessionCannotReadPermissions() {
        when(mapper.activeSession(any())).thenReturn(account(true));status(()->permissions.can("UI-16.read"),403);verify(mapper,never()).grants(any());
    }
    @Test void currentIdentityIsDatabaseDerivedAndUsesServerSeoulTime() {
        assertThat(user.userId()).isEqualTo(42L);assertThat(user.name()).isEqualTo("시험 사용자");
        verify(mapper,atLeastOnce()).activeSession(argThat(q->q.now().equals(LocalDateTime.of(2026,10,23,0,0))));
    }
    @Test void missingRoleNeverBecomesDefaultAdministrator() {
        when(mapper.roles(42L)).thenReturn(List.of());assertThat(user.roleIds()).isEmpty();status(()->permissions.can("UI-16.read"),403);
    }
    @Test void ignoredBooleanCallStillThrowsOnDenial() { status(()->permissions.can("UI-27.update"),403); }
    @Test void administratorStillNeedsExplicitMenuAction() { grants();status(()->permissions.can("UI-25.read"),403); }
    @Test void actionWithoutAdminFlagCannotUseUserManagement() {
        grants(grant(9,DataScope.ALL,true,false));status(()->permissions.can("UI-23.read"),403);
    }
    @Test void financeCannotUseCommonCodeAdministrationDespiteLegacyGrant() {
        grants(grant(3,DataScope.ALL,true,false));status(()->permissions.can("UI-27.read"),403);
    }
    @Test void explicitCustomCommonCodeRoleRetainsExistingPolicy() {
        grants(grant(9,DataScope.ALL,false,false));assertThat(permissions.can("UI-27.read")).isTrue();
    }
    @Test void executiveWriteAndLegacyQuoteApprovalAreDenied() {
        grants(grant(2,DataScope.ALL,true,false));status(()->permissions.can("UI-29.confirm"),403);status(()->permissions.can("UI-13.update"),403);
    }
    @Test void executiveReadAndFinanceClosingRemainAllowed() {
        grants(grant(2,DataScope.ALL,true,false));assertThat(permissions.can("UI-13.read")).isTrue();
        grants(grant(3,DataScope.ALL,true,false));assertThat(permissions.can("UI-14.confirm")).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"UI-14.reopen","UI-13.cancel","UI-19.cancel"})
    void stateTransitionsUseConfirmationNotModification(String key) {
        grants(grant(3,DataScope.ALL,true,false));permissions.can(key);
        verify(mapper).grants(argThat(q->q.action().equals("confirm")));
    }
    @Test void roleScopeAndSensitivePermissionCannotBeCrossComposed() {
        grants(grant(4,DataScope.PART,false,false));
        var scope=permissions.scope("UI-01.read");
        assertThat(scope.permits(new Target(null,null,10L,100L))).isTrue();
        assertThat(scope.permits(new Target(null,null,20L,200L))).isFalse();
        assertThat(scope.permitsSensitive(new Target(null,null,10L,100L))).isFalse();
        status(()->permissions.requireTarget("UI-01.read",new Target(null,null,20L,200L)),404);
    }
    @Test void emptyProjectRelationshipsNeverExpandToAll() {
        grants(grant(5,DataScope.PROJECT,false,false));when(mapper.projects(42L)).thenReturn(List.of());
        assertThat(permissions.scope("UI-01.read").permits(new Target(null,null,10L,100L))).isFalse();
    }
    @Test void selfScopeRequiresActualIdentityOrLinkedDeveloper() {
        grants(grant(9,DataScope.SELF,false,false));var scope=permissions.scope("UI-02.read");
        assertThat(scope.permits(new Target(42L,null,null,null))).isTrue();
        assertThat(scope.permits(new Target(null,7L,null,null))).isTrue();
        assertThat(scope.permits(new Target(null,null,null,null))).isFalse();
    }
    @Test void bulkScopeCheckRejectsAllBeforeMutation() {
        grants(grant(4,DataScope.PART,false,false));
        status(()->permissions.requireAll("UI-01.update",List.of(new Target(null,null,10L,100L),new Target(null,null,20L,200L))),403);
    }
    @Test void permissionRemovalIsReadAgainNotCached() {
        grants(grant(4,DataScope.PART,false,false));assertThat(permissions.can("UI-01.read")).isTrue();
        grants();status(()->permissions.can("UI-01.read"),403);verify(mapper,times(2)).grants(any());
    }
    @Test void productionGuardRejectsBypassButDoesNotRequireSmtp() {
        var env=new MockEnvironment();env.setActiveProfiles("production");new ProductionSecurityGuard(env).afterPropertiesSet();
        env.setProperty("DEV_AUTH_BYPASS","true");assertThatThrownBy(()->new ProductionSecurityGuard(env).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionCannotActivateTestFixturesEvenAlongsideProductionProfile() {
        var env=new MockEnvironment();env.setActiveProfiles("production","test");
        assertThatThrownBy(()->new ProductionSecurityGuard(env).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
    @Test void sessionProofIsRandomAndOnlyDigestIsCompared() {
        String first=AuthenticationProofs.randomSessionId(),second=AuthenticationProofs.randomSessionId();
        assertThat(first.equals(second)).isFalse();assertThat(first.length()).isEqualTo(43);
        assertThat(AuthenticationProofs.matches(first,AuthenticationProofs.hash(first))).isTrue();
        assertThat(AuthenticationProofs.matches(second,AuthenticationProofs.hash(first))).isFalse();
        assertThat(new SessionPrincipal(42L,AuthenticationProofs.hash(first)).toString()).isEqualTo("SessionPrincipal[redacted]");
    }
}
