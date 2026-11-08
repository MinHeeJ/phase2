package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityAuditService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.mockito.ArgumentCaptor;

class ProductionSecurityGuardTest {
    @AfterEach void clear() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @ParameterizedTest @ValueSource(strings={"prod","production","staging"})
    void productionRejectsBypassEvenWithTestProfilePresent(String profile) { var env=new MockEnvironment().withProperty("DEV_AUTH_BYPASS","true");env.setActiveProfiles("test",profile);assertThatThrownBy(()->new ProductionSecurityGuard(env).afterPropertiesSet()).isInstanceOf(IllegalStateException.class); }
    @Test void defaultProfileIsNotImplicitDevelopment() { assertThatThrownBy(()->new ProductionSecurityGuard(new MockEnvironment().withProperty("DEV_AUTH_BYPASS","true")).afterPropertiesSet()).isInstanceOf(IllegalStateException.class); }
    @Test void normalStartupNeedsNeitherSmtpNorNewMandatorySettings() { new ProductionSecurityGuard(new MockEnvironment()).afterPropertiesSet(); }
    @Test void syntheticFixtureFlagsCannotRunInProduction() { var env=new MockEnvironment().withProperty("sicost.ai.synthetic-test-enabled","true");env.setActiveProfiles("production");assertThatThrownBy(()->new ProductionSecurityGuard(env).afterPropertiesSet()).isInstanceOf(IllegalStateException.class); }
    @Test void explicitNonProductionFixtureProfileDoesNotAuthorizeAnythingByItself() { var env=new MockEnvironment().withProperty("sicost.ai.synthetic-test-enabled","true");env.setActiveProfiles("test");new ProductionSecurityGuard(env).afterPropertiesSet();AuthorizationTest.status(()->new CurrentUser().userId(),401); }
    @Test void missingAcceptanceOrOperationalReceiptNeverMeansFinalCompletion() { var ac=new HashMap<String,Boolean>();for(int id=1601;id<=1620;id++) ac.put("AC-"+id,true);var all=EnumSet.allOf(SecurityDeploymentGate.OperationalCheck.class);assertThat(SecurityDeploymentGate.evaluate(ac,all).ready()).isTrue();ac.put("AC-1618",false);assertThat(SecurityDeploymentGate.evaluate(ac,all).missing()).contains("AC-1618");ac.put("AC-1618",true);all.remove(SecurityDeploymentGate.OperationalCheck.DATABASE_RESTORE);assertThat(SecurityDeploymentGate.evaluate(ac,all).ready()).isFalse(); }
    @Test void auditContainsActorTimePurposeAndResultButNoRawPayload() {
        AuthorizationTest.authenticate(11L);var mapper=mock(SecurityAuditMapper.class);
        var audit=new SecurityAuditService(mapper,Clock.fixed(Instant.parse("2026-10-31T15:00:00Z"),ZoneOffset.UTC));
        audit.record(Event.CONTACT_VIEW,"UI-02",101L,Result.SUCCESS,Purpose.STAFF_OPERATION);
        var entry=ArgumentCaptor.forClass(SecurityAuditMapper.Entry.class);verify(mapper).append(entry.capture());
        assertThat(entry.getValue().actor()).isEqualTo(11L);assertThat(entry.getValue().now()).isEqualTo(LocalDateTime.of(2026,11,1,0,0));
        assertThat(entry.getValue().detail()).isEqualTo("{\"result_cd\":\"SUCCESS\",\"purpose_desc\":\"STAFF_OPERATION\"}");
    }
    @Test void auditFailureIsObservableAndCannotLeakUnderlyingException() {
        AuthorizationTest.authenticate(11L);var mapper=mock(SecurityAuditMapper.class);String secret=UUID.randomUUID().toString();
        doThrow(new IllegalStateException(secret)).when(mapper).append(any());
        var audit=new SecurityAuditService(mapper,Clock.systemUTC());
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(SecurityAuditService.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            AuthorizationTest.status(()->audit.record(Event.DOWNLOAD,"UI-02",101L,Result.DENIED,null),503);
            assertThat(audit.failureCount()).isEqualTo(1);assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("SECURITY_AUDIT_WRITE_FAILED").doesNotContain(secret);
            assertThat(appender.list.get(0).getThrowableProxy()).isNull();
        } finally { logger.detachAppender(appender);appender.stop(); }
    }
    @Test void operationalBootstrapIsOneTimeAndClearsCallerSecret() {
        var mapper=mock(SecurityStateMapper.class);when(mapper.lockAdministratorRoles()).thenReturn(List.of(1L));when(mapper.administratorCount()).thenReturn(0L,1L);when(mapper.createAdministrator(any())).thenReturn(11L);
        var initializer=new InitialAdministratorInitializer(mapper);char[] password=(UUID.randomUUID()+"!7a").toCharArray();
        assertThat(initializer.initialize("synthetic.admin",password,"시험 관리자","admin@example.invalid")).isEqualTo(11L);assertThat(password).containsOnly('\0');
        char[] other=(UUID.randomUUID()+"!7a").toCharArray();AuthorizationTest.status(()->initializer.initialize("synthetic.other",other,"시험 관리자","other@example.invalid"),409);assertThat(other).containsOnly('\0');verify(mapper,times(1)).createAdministrator(any());
    }
}
