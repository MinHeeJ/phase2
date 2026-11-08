package com.sicost.authentication;

import com.sicost.common.security.AuthenticationProof;
import com.sicost.systemadmin.AdministrationMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmokeAccountInitializerTest {
    private final AuthenticationMapper authentication=mock(AuthenticationMapper.class);
    private final AdministrationMapper administration=mock(AdministrationMapper.class);
    private final SmokeAccountInitializer initializer=new SmokeAccountInitializer(authentication,administration);
    private String login() { return UUID.randomUUID().toString(); }
    private String password() { return UUID.randomUUID().toString().replace("-",""); }
    private void role() { when(administration.roles()).thenReturn(List.of(Map.of("roleId",2L,"name","경영진","useYn","Y"))); }

    @Test void missingOrBlankSlotsDoNotAccessDatabase() {
        initializer.initialize(null,password(),"경영진");
        initializer.initialize(login(),null,"경영진");
        initializer.initialize(login(),password(),null);
        initializer.initialize("",password(),"경영진");
        initializer.initialize(login()," ","경영진");
        initializer.initialize(login(),password(),"");
        verifyNoInteractions(authentication,administration);
    }
    @Test void generatedPasswordWithoutSpecialCharactersIsHashedWithoutChangingIt() {
        role();String login=login(),password=password();
        when(authentication.login(login)).thenReturn(null);
        when(administration.insertUser(anyMap())).thenReturn(21L);
        initializer.initialize(login,password,"경영진");
        ArgumentCaptor<Map<String,Object>> write=ArgumentCaptor.forClass(Map.class);
        verify(authentication).password(write.capture());
        // Assert only the result, so failed assertions cannot print a credential/hash.
        assertThat(AuthenticationProof.matches(password,(String)write.getValue().get("hash"))).isTrue();
        verify(administration).addRole(21L,2L);
        verify(administration).revokeUser(21L);
    }
    @Test void everyRestartUpdatesSameAccountAndReplacesRoles() {
        role();String login=login();when(authentication.login(login)).thenReturn(Map.of("userId",21L));
        initializer.initialize(login,password(),"경영진");
        initializer.initialize(login,password(),"경영진,경영진");
        verify(administration,never()).insertUser(anyMap());
        verify(authentication,times(2)).password(anyMap());
        verify(administration,times(2)).clearRoles(21L);
        verify(administration,times(2)).addRole(21L,2L);
    }
    @Test void unknownRoleAndOverlongPasswordFailBeforeWrites() {
        role();
        assertThatThrownBy(()->initializer.initialize(login(),password(),"unknown")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->initializer.initialize(login(),password().repeat(3),"경영진")).isInstanceOf(IllegalStateException.class);
        verify(administration,never()).insertUser(anyMap());verify(authentication,never()).password(anyMap());
    }
}
