package com.sicost.common.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SmokeAccountInitializerTest {
    @Test void missingOrPartialSlotsDoNotTouchTheDatabase() {
        var mapper=mock(SmokeAccountMapper.class);
        var encoder=mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        var initializer=new SmokeAccountInitializer(mapper,encoder);
        String login=UUID.randomUUID().toString(),password=UUID.randomUUID().toString();
        initializer.initialize(null,null,null);
        initializer.initialize(login,null,"재무 담당");
        initializer.initialize(null,password,"재무 담당");
        initializer.initialize(login,password,null);
        initializer.initialize(" ",password,"재무 담당");
        initializer.initialize(login,"","재무 담당");
        initializer.initialize(login,password," , ");
        verifyNoInteractions(mapper,encoder);
    }

    @Test void eachStartupHashesAndReplacesPasswordAndRolesForTheSameAccount() {
        var mapper=mock(SmokeAccountMapper.class);
        var encoder=new BCryptPasswordEncoder(4);
        var initializer=new SmokeAccountInitializer(mapper,encoder);
        when(mapper.roles(List.of("재무 담당"))).thenReturn(List.of(17L));
        when(mapper.upsert(any())).thenReturn(42L);
        String login=UUID.randomUUID().toString();
        String first=UUID.randomUUID()+"Aa9!",second=UUID.randomUUID()+"Aa9!";
        initializer.initialize(login,first," 재무 담당,재무 담당 ");
        initializer.initialize(login,second,"재무 담당");
        var writes=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(mapper,times(2)).upsert(writes.capture());
        var values=writes.getAllValues();
        assertThat(values.stream().allMatch(value->login.equals(value.get("login")))).isTrue();
        assertThat(encoder.matches(first,(String)values.get(0).get("hash"))).isTrue();
        assertThat(encoder.matches(second,(String)values.get(1).get("hash"))).isTrue();
        assertThat(encoder.matches(first,(String)values.get(1).get("hash"))).isFalse();
        verify(mapper,times(2)).clearRoles(42L);
        verify(mapper,times(2)).assign(Map.of("id",42L,"roles",List.of(17L)));
    }

    @Test void unknownRolesCannotCreateAnAccount() {
        var mapper=mock(SmokeAccountMapper.class);
        var encoder=mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        when(mapper.roles(any())).thenReturn(List.of());
        var initializer=new SmokeAccountInitializer(mapper,encoder);
        assertThatThrownBy(()->initializer.initialize(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"없는 역할"))
            .isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).upsert(any());
        verifyNoInteractions(encoder);
    }
}
