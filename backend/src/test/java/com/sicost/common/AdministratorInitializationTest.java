package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdministratorInitializationTest {
    @Test void absentBootstrapSecretsHaveNoDatabaseSideEffects() {
        var mapper=mock(AccessPolicyMapper.class);var service=new InitialAdministratorService(mapper,new BCryptPasswordEncoder(12));
        assertThat(service.initialize(null,null,null)).isFalse();verifyNoInteractions(mapper);
    }
    @Test void bootstrapHashesPasswordAssignsExistingRoleAndCannotReuseConsumedInitialization() {
        var mapper=mock(AccessPolicyMapper.class);var encoder=new BCryptPasswordEncoder(12);
        var service=new InitialAdministratorService(mapper,encoder);
        String password=AuthenticationProofs.randomSessionId()+"a9!";
        when(mapper.insertInitialAdministrator(any())).thenReturn(42L).thenReturn(null);
        assertThat(service.initialize(" synthetic-admin ",password,"synthetic@example.invalid")).isTrue();
        verify(mapper).insertInitialAdministrator(argThat(w->w.loginId().equals("synthetic-admin") && encoder.matches(password,w.passwordHash())));
        verify(mapper).assignInitialAdministrator(42L);
        assertThat(service.initialize("synthetic-admin",password,"synthetic@example.invalid")).isFalse();
        verify(mapper,times(1)).assignInitialAdministrator(any());
    }
    @Test void weakPasswordCannotReachDatabaseOrHashing() {
        var mapper=mock(AccessPolicyMapper.class);var encoder=mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        assertThatThrownBy(()->new InitialAdministratorService(mapper,encoder).initialize("synthetic-admin","x","synthetic@example.invalid")).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper,encoder);
    }
    @Test void confirmationIsExactAndPasswordWhitespaceIsNeverTrimmed() {
        String password=" "+AuthenticationProofs.randomSessionId()+"a9! ";
        PasswordRules.validate(password,password);
        assertThatThrownBy(()->PasswordRules.validate(password,password.trim())).isInstanceOf(ApiException.class);
    }
    @Test void lastAdministratorUsesSharedDatabaseLockAndRejectsReduction() {
        var mapper=mock(AccessPolicyMapper.class);var guard=new LastAdministratorGuard(mapper);
        guard.lock();verify(mapper).lockAdministration();
        when(mapper.recoveryAdministratorCount()).thenReturn(0L);
        assertThatThrownBy(guard::requireRecovery).isInstanceOfSatisfying(ApiException.class,e->{
            assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo("LAST_ADMIN");
        });
        when(mapper.recoveryAdministratorCount()).thenReturn(1L);guard.requireRecovery();
    }
}
