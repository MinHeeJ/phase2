package com.sicost.common;

import com.sicost.common.ai.*;
import com.sicost.common.api.ApiException;
import java.time.*;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiBoundaryTest {
    final MockEnvironment environment = new MockEnvironment();
    final AiAccessGuard access = new AiAccessGuard(environment);
    final Instant start = Instant.parse("2026-10-31T15:00:00Z");

    @Test void ordinaryDevelopmentAllowsAnonymousWithoutChangingLegacyPermission() {
        access.requireDevelopment();
        access.requireAction(AiActions.RECOMMEND);
        assertThat(new com.sicost.common.security.PermissionService().can("UI-12.create")).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"prod", "production", "staging", "unknown"})
    void nonDevelopmentProfileCannotBeOverriddenByTestSettings(String profile) {
        environment.setActiveProfiles("test", profile);
        environment.setProperty("sicost.ai.environment", "test");
        environment.setProperty("sicost.ai.synthetic-test-enabled", "true");
        forbidden(access::requireDevelopment);
    }
    @Test void productionPropertyBlocksEvenWithoutProfiles() {
        environment.setProperty("sicost.ai.environment", "production");
        forbidden(access::requireDevelopment);
    }
    @Test void futureActionHookDoesNotAcceptUnknownOrWeeklyDispatchActions() {
        assertThatThrownBy(() -> access.requireAction("UI-30.weekly-send"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThat(AiActions.key("previewStaffRecommendationCriteria")).isEqualTo("UI-30.recommend");
        assertThat(AiActions.key("saveAnalysisMessageFeedback")).isEqualTo("UI-26.ask");
    }
    @Test void realPersonalCostAndReverseDerivableResponsesAreDeniedBeforeSourceReads() {
        var database = mock(DataSource.class);
        var sensitive = new AiSensitiveGuard(access, environment, database);
        forbidden(() -> sensitive.requireNonSensitive(true, false));
        forbidden(() -> sensitive.requireNonSensitive(false, true));
        verifyNoInteractions(database);
    }
    @Test void nonSensitiveReadsAndIndependentVirtualInputsNeedNoPayrollConnection() {
        var database = mock(DataSource.class);
        var sensitive = new AiSensitiveGuard(access, environment, database);
        sensitive.requireNonSensitive(false, false);
        sensitive.requireIndependentVirtual(Set.of());
        verifyNoInteractions(database);
    }
    @Test void virtualInputsCannotCombineWithRealPersonReferences() {
        var sensitive = new AiSensitiveGuard(access, environment, mock(DataSource.class));
        forbidden(() -> sensitive.requireIndependentVirtual(Set.of(1L)));
    }
    @Test void syntheticFlagAloneDoesNotEnableSensitiveReads() {
        environment.setProperty("sicost.ai.synthetic-test-enabled", "true");
        var database = mock(DataSource.class);
        forbidden(() -> new AiSensitiveGuard(access, environment, database).requireSyntheticDatabase());
        verifyNoInteractions(database);
    }
    @Test void testProfileWithRealDatabaseIsNotSynthetic() throws Exception {
        environment.setActiveProfiles("test");
        environment.setProperty("sicost.ai.synthetic-test-enabled", "true");
        var database = mock(DataSource.class);
        var connection = mock(java.sql.Connection.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("sicost");
        forbidden(() -> new AiSensitiveGuard(access, environment, database).requireSyntheticDatabase());
        verify(connection).close();
    }
    @Test void isolatedSyntheticDatabaseCanExerciseSensitiveFormulaOnlyInTestProfile() throws Exception {
        environment.setActiveProfiles("test");
        environment.setProperty("sicost.ai.synthetic-test-enabled", "true");
        var database = mock(DataSource.class);
        var connection = mock(java.sql.Connection.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("sicost_test_" + "a".repeat(32));
        new AiSensitiveGuard(access, environment, database).requireSyntheticDatabase();
        verify(connection).close();
    }
    @Test void productionSyntheticRequestDoesNotEvenOpenDatabase() {
        environment.setActiveProfiles("production", "test");
        environment.setProperty("sicost.ai.synthetic-test-enabled", "true");
        var database = mock(DataSource.class);
        forbidden(() -> new AiSensitiveGuard(access, environment, database).requireSyntheticDatabase());
        verifyNoInteractions(database);
    }
    @Test void independentOwnerDiffersFromServletSessionIdAndOtherBrowserOwner() {
        var owners = ownersAt(start);
        var first = new MockHttpServletRequest();
        var second = new MockHttpServletRequest();
        String hash = owners.ownerHash(first);
        assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(first.getSession().getId());
        assertThat(owners.ownerHash(first)).isEqualTo(hash);
        assertThat(owners.ownerHash(second)).isNotEqualTo(hash);
        forbidden(() -> owners.requireOwner(second, hash));
        forbidden(() -> owners.requireOwner(first, null));
    }
    @Test void suppliedHeaderQueryAndSessionIdNeverAdoptStoredOwner() {
        var owners = ownersAt(start);
        var first = new MockHttpServletRequest();
        String hash = owners.ownerHash(first);
        var other = new MockHttpServletRequest();
        other.addHeader("X-AI-Owner", hash); other.setParameter("owner", hash);
        other.setSession(new MockHttpSession(null, first.getSession().getId()));
        forbidden(() -> owners.requireOwner(other, hash));
    }
    @Test void ownerExpiresAtThirtyMinutesEvenWhenServletSessionStillLives() {
        var request = new MockHttpServletRequest();
        String original = ownersAt(start).ownerHash(request);
        forbidden(() -> ownersAt(start.plusSeconds(1800)).requireOwner(request, original));
        assertThat(ownersAt(start.plusSeconds(1800)).ownerHash(request)).isNotEqualTo(original);
    }
    @Test void successfulAccessRefreshesOnlyOwnerIdleDeadline() {
        var request = new MockHttpServletRequest();
        String original = ownersAt(start).ownerHash(request);
        ownersAt(start.plusSeconds(1700)).requireOwner(request, original);
        ownersAt(start.plusSeconds(3400)).requireOwner(request, original);
        assertThat(ownersAt(start.plusSeconds(3401)).ownerHash(request)).isEqualTo(original);
    }
    @Test void rejectedOwnershipDoesNotRefreshDeadline() {
        var request = new MockHttpServletRequest();
        String original = ownersAt(start).ownerHash(request);
        forbidden(() -> ownersAt(start.plusSeconds(1700)).requireOwner(request, "f".repeat(64)));
        forbidden(() -> ownersAt(start.plusSeconds(1800)).requireOwner(request, original));
    }
    @Test void inFlightWorkMustRevalidateOwnerBeforeCommit() {
        var request = new MockHttpServletRequest();
        String original = ownersAt(start).ownerHash(request);
        forbidden(() -> ownersAt(start.plusSeconds(1801)).assertCurrent(request, original));
    }
    private AiOwnerSession ownersAt(Instant time) {
        return new AiOwnerSession(Clock.fixed(time, ZoneOffset.UTC));
    }
    static void forbidden(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403); assertThat(e.code()).isEqualTo("FORBIDDEN");
        });
    }
}
