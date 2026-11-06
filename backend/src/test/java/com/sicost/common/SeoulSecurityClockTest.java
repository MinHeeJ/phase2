package com.sicost.common;

import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SeoulSecurityClockTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-11-05T15:00:00Z"),ZoneOffset.UTC);
    @Test void resignationBeginsAtSeoulMidnightIndependentOfJvmOrClientZone() {
        assertThat(SecurityTime.now(clock)).isEqualTo(LocalDateTime.of(2026,11,6,0,0));
        assertThat(SecurityTime.active("ACTIVE",LocalDate.of(2026,11,6),Clock.offset(clock,Duration.ofNanos(-1)))).isTrue();
        assertThat(SecurityTime.active("ACTIVE",LocalDate.of(2026,11,6),clock)).isFalse();
    }
    @Test void exactThirtyMinuteIdleBoundaryExpires() {
        LocalDateTime now=SecurityTime.now(clock);
        assertThat(SecurityTime.sessionValid(now.minusMinutes(29),now.plusMinutes(1),null,clock)).isTrue();
        assertThat(SecurityTime.sessionValid(now.minusMinutes(30),now,null,clock)).isFalse();
    }
    @Test void revocationAndFutureClientActivityCannotExtendSession() {
        LocalDateTime now=SecurityTime.now(clock);
        assertThat(SecurityTime.sessionValid(now,now.plusMinutes(30),now,clock)).isFalse();
        assertThat(SecurityTime.sessionValid(now.plusHours(1),now.plusHours(2),null,clock)).isFalse();
    }
    @Test void lockedAndDisabledAreNeverActive() {
        assertThat(SecurityTime.active("LOCKED",null,clock)).isFalse();
        assertThat(SecurityTime.active("DISABLED",null,clock)).isFalse();
    }
    @Test void proofsAreUnpredictableHashedAndVerifiedWithoutPlaintextStorage() {
        String first=AuthenticationProofs.randomId(),second=AuthenticationProofs.randomId();
        assertThat(first).hasSize(43).isNotEqualTo(second);
        String hash=AuthenticationProofs.hash(first);
        assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(first);
        assertThat(AuthenticationProofs.matches(first,hash)).isTrue();
        assertThat(AuthenticationProofs.matches(second,hash)).isFalse();
        assertThat(AuthenticationProofs.matches(null,hash)).isFalse();
        assertThat(AuthenticationProofs.matches(first,"not-a-hash")).isFalse();
    }
    @Test void otpHasSixDigitsAndIsBoundToThePreauthenticationSecret() {
        String code=AuthenticationProofs.otp(),chain=AuthenticationProofs.randomId();
        assertThat(code).matches("[0-9]{6}");
        assertThat(AuthenticationProofs.otpHash(chain,code)).isNotEqualTo(AuthenticationProofs.otpHash(AuthenticationProofs.randomId(),code));
    }
}
