package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthorizationTest {
    final SecurityStateMapper mapper=mock(SecurityStateMapper.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-31T15:00:00Z"),ZoneOffset.UTC);
    final AuthorizationService authorization=new AuthorizationService(mapper,new CurrentUser(),clock);
    @BeforeEach void setup() {
        authenticate(11L);
        when(mapper.account(11L)).thenReturn(new Account(11L,"시험 사용자",101L,"ACTIVE",null,false));
        when(mapper.parts(11L)).thenReturn(List.of(1L));when(mapper.projects(11L)).thenReturn(List.of(10L));
    }
    static void authenticate(long id) { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new SecurityPrincipal(id,"시험 사용자",List.of(4L),"a".repeat(64),false),null,List.of())); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    Permission grant(long id,DataScope scope,String actions,boolean sensitive) { return new Permission(id,"영업 파트장",scope,false,sensitive,false,"UI-16",actions,false,false); }
    PermissionService permissions() { var service=new PermissionService();service.setAuthorization(authorization);return service; }
    static void status(Runnable call,int status) { assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(status)); }
    @Test void anonymousHasNoDefaultActorRoleOrAllScope() { clear();status(()->new CurrentUser().userId(),401);status(()->new PermissionService().can("UI-16.read"),401); }
    @Test void ignoredPermissionBooleanStillThrowsBeforeBusinessWrite() { when(mapper.permissions(11L)).thenReturn(List.of());status(()->permissions().can("UI-16.update"),403); }
    @Test void existingPermissionSignatureReturnsTrueOnlyForExplicitAction() { when(mapper.permissions(11L)).thenReturn(List.of(grant(4,DataScope.PART,"YNNNNNN",false)));assertThat(permissions().can("UI-16.read")).isTrue(); }
    @ParameterizedTest @ValueSource(strings={"create","update","delete","upload","download","confirm","unknown"})
    void inquiryNeverGrantsOtherActions(String action) { when(mapper.permissions(11L)).thenReturn(List.of(grant(4,DataScope.PART,"YNNNNNN",false)));status(()->authorization.require("UI-16."+action),403); }
    @Test void menuDenyHasNoAdministratorBypass() { when(mapper.permissions(11L)).thenReturn(List.of(new Permission(1L,"시스템 관리자",DataScope.ALL,true,true,true,"UI-23","NNNNNNN",true,true)));status(()->authorization.require("UI-23.read"),403); }
    @Test void administrativeActionAlsoRequiresAdminOnSameRole() { when(mapper.permissions(11L)).thenReturn(List.of(new Permission(4L,"일반",DataScope.ALL,false,true,true,"UI-23","YYYYYYY",true,true)));status(()->authorization.require("UI-23.read"),403); }
    @Test void crossRoleScopeAndSensitivePermissionCannotBeCombined() {
        when(mapper.permissions(11L)).thenReturn(List.of(grant(4,DataScope.PART,"YNNNNNN",false),grant(5,DataScope.PROJECT,"NNNNNNN",true)));
        var scope=authorization.scope("UI-16.read");
        assertThat(scope.allows(new Resource(null,2L,10L,null))).isFalse();
        assertThat(scope.sensitive(new Resource(null,1L,10L,null))).isFalse();
        status(()->authorization.requireRow("UI-16.read",new Resource(null,2L,10L,null)),404);
    }
    @Test void roleRevocationIsReadFromDatabaseOnNextCall() { when(mapper.permissions(11L)).thenReturn(List.of(grant(4,DataScope.ALL,"YYYYYYY",true)),List.of());authorization.require("UI-16.read");status(()->authorization.require("UI-16.read"),403); }
    @Test void emptyPartRelationshipIsNotPromotedToAll() { when(mapper.parts(11L)).thenReturn(List.of());when(mapper.permissions(11L)).thenReturn(List.of(grant(4,DataScope.PART,"YYYYYYY",false)));status(()->authorization.require("UI-16.read"),403); }
    @Test void emptyProjectRelationshipIsNotPromotedToAll() { when(mapper.projects(11L)).thenReturn(List.of());when(mapper.permissions(11L)).thenReturn(List.of(grant(5,DataScope.PROJECT,"YYYYYYY",false)));status(()->authorization.require("UI-16.read"),403); }
    @Test void selfRequiresMatchingUserOrDeveloperIdentity() { var s=new ScopePredicate(11L,101L,new Assignments(List.of(),List.of()),List.of(grant(5,DataScope.SELF,"YNNNNNN",false)));assertThat(s.allows(new Resource(null,null,null,null))).isFalse();assertThat(s.allows(new Resource(11L,null,null,null))).isTrue();assertThat(s.allows(new Resource(null,null,null,101L))).isTrue(); }
    @Test void forbiddenBulkTargetIsRejectedBeforeCallerMutatesAnything() { when(mapper.permissions(11L)).thenReturn(List.of(grant(4,DataScope.PART,"YYYNNNN",false)));status(()->authorization.requireAll("UI-16.update",List.of(new Resource(null,1L,null,null),new Resource(null,2L,null,null))),403); }
    @Test void seoulRetirementBoundaryIgnoresClientClock() { when(mapper.account(11L)).thenReturn(new Account(11L,"시험 사용자",null,"ACTIVE",LocalDate.of(2026,11,1),false));status(()->authorization.require("UI-16.read"),401);verify(mapper,never()).permissions(any()); }
    @ParameterizedTest @ValueSource(strings={"DISABLED","LOCKED"}) void changedAccountStatusBlocksExistingPrincipal(String state) { when(mapper.account(11L)).thenReturn(new Account(11L,"시험 사용자",null,state,null,false));status(()->authorization.require("UI-16.read"),401); }
    @Test void requiredPasswordChangeCannotEnterBusinessService() { when(mapper.account(11L)).thenReturn(new Account(11L,"시험 사용자",null,"ACTIVE",null,true));status(()->authorization.require("UI-16.read"),403); }
}
