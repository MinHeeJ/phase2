package com.sicost.common;

import static com.sicost.common.validation.AssignmentRules.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AssignmentRulesTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    final YearMonth october = YearMonth.of(2026, 10);
    Period period(String start, String end) {
        return new Period(LocalDate.parse(start), LocalDate.parse(end), BigDecimal.ONE);
    }
    void error(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @ParameterizedTest @CsvSource({"1,A-0001", "9999,A-9999", "10000,A-10000", "123456,A-123456"})
    void allocatedNumberNeverTruncates(long id, String expected) {
        assertThat(allocated(id)).isEqualTo(expected);
    }
    @ParameterizedTest @ValueSource(longs = {0, -1})
    void numberRequiresAllocatedSequenceId(long id) {
        error(() -> allocated(id), 400, "INVALID_INPUT");
    }
    @ParameterizedTest @CsvSource({
        "2026-10-16,2026-10-31,1,2026-10,0.52",
        "2026-10-14,2026-10-14,1,2026-10,0.03",
        "2026-10-08,2026-10-31,1,2026-10,0.77",
        "2026-10-01,2026-10-31,0.5,2026-10,0.50",
        "2024-02-29,2024-02-29,1,2024-02,0.03",
        "2026-11-01,2026-11-30,1,2026-10,0.00"
    })
    void inclusiveCalendarMmRoundsOnlyOnce(String start, String end, String rate, String month, String expected) {
        var period = new Period(LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(rate));
        assertThat(automaticMm(period, YearMonth.parse(month))).isEqualByComparingTo(expected);
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-0.1", "1.01", "0.123"})
    void assignmentRateRejectsInvalidValue(String value) {
        error(() -> new Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), new BigDecimal(value)),
            400, "INVALID_INPUT");
    }
    @Test void reversePeriodIsInvalid() {
        error(() -> period("2026-10-31", "2026-10-01"), 400, "INVALID_INPUT");
    }
    @Test void reconstructionPreservesManualAndEveryClosedRowAndDropsOnlyOpenOutsideMonths() {
        var september = YearMonth.of(2026, 9);
        var closedRow = new Month(september, new BigDecimal("0.52"), new BigDecimal("0.40"), true);
        var manual = new Month(october, new BigDecimal("0.52"), new BigDecimal("0.50"), true);
        var outside = new Month(YearMonth.of(2027, 1), BigDecimal.ONE, BigDecimal.ONE, false);
        var automatic = new Month(october.plusMonths(1), BigDecimal.ONE, BigDecimal.ONE, false);
        var rows = rebuildMonths(period("2026-10-08", "2026-11-15"),
            List.of(closedRow, manual, automatic, outside), Set.of(september));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).isSameAs(closedRow);
        assertThat(rows.get(1).automatic()).isEqualByComparingTo("0.77");
        assertThat(rows.get(1).quantity()).isEqualByComparingTo("0.50");
        assertThat(rows.get(1).manual()).isTrue();
        assertThat(rows.get(2).quantity()).isEqualByComparingTo("0.50");
        assertThat(rows.get(2).manual()).isFalse();
    }
    @Test void newlyRegisteredClosedMonthsAreNeverCreated() {
        var rows = rebuildMonths(period("2026-08-01", "2026-10-31"), List.of(),
            Set.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9)));
        assertThat(rows).extracting(Month::month).containsExactly(october);
    }
    @Test void manualAutomaticAndZeroEditsSetMarkerFromRoundedValue() {
        var row = new Month(october, new BigDecimal("0.52"), new BigDecimal("0.52"), false);
        assertThat(editedMonth(row, new BigDecimal("0.50"), Set.of()).manual()).isTrue();
        assertThat(editedMonth(row, new BigDecimal("0.523"), Set.of()).manual()).isFalse();
        assertThat(editedMonth(row, BigDecimal.ZERO, Set.of()).quantity()).isEqualByComparingTo("0.00");
    }
    @ParameterizedTest @ValueSource(strings = {"-0.001", "1.001", "1.20"})
    void manualMmRangeIsCheckedBeforeRounding(String value) {
        error(() -> manualMm(new BigDecimal(value)), 400, "INVALID_INPUT");
    }
    @Test void closedEditIsConflictEvenForEqualValues() {
        var row = new Month(october, BigDecimal.ONE, BigDecimal.ONE, false);
        error(() -> editedMonth(row, BigDecimal.ONE, Set.of(october)), 409, "CLOSED_MONTH");
    }
    @Test void financialDateShiftsWithoutChangingRequestedDate() {
        var requested = LocalDate.of(2026, 9, 1);
        assertThat(financialDate(requested, period("2026-08-16", "2026-12-31"),
            Set.of(YearMonth.of(2026, 9)))).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(requested).isEqualTo(LocalDate.of(2026, 9, 1));
    }
    @Test void assignmentEndingBeforeFirstOpenDayHasHistoryOnly() {
        assertThat(financialDate(LocalDate.of(2026, 9, 1), period("2026-09-16", "2026-09-30"),
            Set.of(YearMonth.of(2026, 9)))).isNull();
    }
    @Test void initialRateIsBoundedByFirstOpenAssignmentDay() {
        assertThat(financialDate(LocalDate.of(2026, 10, 1), period("2026-10-16", "2026-12-31"), Set.of()))
            .isEqualTo(LocalDate.of(2026, 10, 16));
    }
    @Test void replacementCopiesRateOnDayAndStrictlyFutureSchedulesOnlyThroughOriginalEnd() {
        var original = List.of(new Rate(LocalDate.of(2026, 10, 1), 7000000L),
            new Rate(LocalDate.of(2026, 11, 16), 7500000L),
            new Rate(LocalDate.of(2026, 12, 1), 8000000L),
            new Rate(LocalDate.of(2027, 1, 1), 9000000L));
        var inherited = inheritedRates(original, LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 31));
        assertThat(inherited).containsExactly(original.get(1), original.get(2));
        assertThat(original).hasSize(4);
    }
    @ParameterizedTest @ValueSource(strings = {"TK", "IN"})
    void nonpricedProjectsUseHarmlessStorageBasisButNullWire(String type) {
        assertThat(pricing(type, 7000000L, "HOUR")).isEqualTo(new Pricing(null, "MONTH", null));
    }
    @ParameterizedTest @ValueSource(strings = {"MO", "DP"})
    void pricedProjectsRequirePositiveAmountAndSelectedBasis(String type) {
        error(() -> pricing(type, null, "MONTH"), 400, "INVALID_INPUT");
        error(() -> pricing(type, 0L, "MONTH"), 400, "INVALID_INPUT");
        error(() -> pricing(type, 1L, null), 400, "INVALID_INPUT");
        assertThat(pricing(type, 1L, "DAY")).isEqualTo(new Pricing(1L, "DAY", "DAY"));
    }
    @Test void fixedSeoulClockDistinguishesYesterdayAndToday() {
        assertThat(retroactive(LocalDate.of(2026, 10, 13), clock)).isTrue();
        assertThat(retroactive(LocalDate.of(2026, 10, 14), clock)).isFalse();
    }
    @Test void startInPastRequiresActiveReasonIncludingUpload() {
        error(() -> requireReason(ChangeType.START, LocalDate.of(2026, 10, 13), clock, null, null),
            400, "INVALID_INPUT");
        error(() -> requireReason(ChangeType.START, LocalDate.of(2026, 10, 13), clock, null,
            new Reason("CUST", null, false)), 400, "INVALID_INPUT");
        requireReason(ChangeType.START, LocalDate.of(2026, 10, 13), clock, null, new Reason("CUST", null, true));
    }
    @Test void closedDateRequiresReasonEvenWhenNotRetroactive() {
        error(() -> requireReason(ChangeType.START, LocalDate.of(2026, 10, 14), clock, october, null),
            400, "INVALID_INPUT");
    }
    @ParameterizedTest @EnumSource(value = ChangeType.class, names = {"EARLY_END", "REPLACE", "FIX"})
    void intrinsicallyReasonedChangesRequireReasonForFutureDates(ChangeType type) {
        error(() -> requireReason(type, LocalDate.of(2026, 12, 1), clock, null, null), 400, "INVALID_INPUT");
    }
    @Test void futureStartMayOmitReasonButInactiveOptionalReasonIsInvalid() {
        requireReason(ChangeType.START, LocalDate.of(2026, 10, 16), clock, null, null);
        error(() -> requireReason(ChangeType.START, LocalDate.of(2026, 10, 16), clock, null,
            new Reason("OLD", null, false)), 400, "INVALID_INPUT");
    }
    @Test void uploadProvenanceAndDescriptionAreBothPreservedWithinPhysicalLength() {
        assertThat(historyReason(123L, "고객 일정 변경")).isEqualTo("엑셀 업로드 #123 · 고객 일정 변경");
        assertThat(historyReason(Long.MAX_VALUE, "가".repeat(200)).length()).isLessThanOrEqualTo(500);
        error(() -> historyReason(1L, "가".repeat(201)), 400, "INVALID_INPUT");
    }
    @ParameterizedTest @ValueSource(strings = {"END", "LOST"})
    void closedProjectIsDomainConflictNotPermission(String state) {
        error(() -> requireProjectWritable("Y", state), 409, "PROJECT_CLOSED");
    }
    @Test void disabledProjectCannotBeWritten() {
        error(() -> requireProjectWritable("N", "RUN"), 409, "PROJECT_CLOSED");
    }
    @ParameterizedTest @EnumSource(value = Mutation.class,
        names = {"PERIOD", "EXTEND", "EARLY_END", "EXT_CHANCE", "REPLACE", "CANCEL"})
    void replacedOriginalRejectsEveryDisallowedOperation(Mutation operation) {
        error(() -> requireMutation("REPLACED", operation, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15)),
            409, "ASSIGNMENT_LOCKED");
    }
    @ParameterizedTest @EnumSource(value = Mutation.class, names = {"MONTHS", "RATE", "FIX"})
    void replacedOriginalAllowsOnlyEligibleOperations(Mutation operation) {
        requireMutation("REPLACED", operation, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15));
    }
    @Test void replacedOriginalRejectsRateAfterReplacement() {
        error(() -> requireMutation("REPLACED", Mutation.RATE, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 15)),
            400, "INVALID_INPUT");
    }
    @ParameterizedTest @EnumSource(Mutation.class)
    void cancelledAssignmentIsReadOnly(Mutation operation) {
        error(() -> requireMutation("CANCELLED", operation, null, null), 409, "ASSIGNMENT_LOCKED");
    }
    @ParameterizedTest @EnumSource(value = ChangeType.class, names = {"START", "FIX"})
    void nonfixableHistoryKindsAreRejected(ChangeType type) {
        error(() -> requireFixTarget(1, 1, type, false), 409, "NOT_FIXABLE");
    }
    @Test void alreadyFixedHistoryIsRejected() {
        error(() -> requireFixTarget(1, 1, ChangeType.RATE, true), 409, "NOT_FIXABLE");
    }
    @Test void foreignHistoryIsNotFound() {
        error(() -> requireFixTarget(1, 2, ChangeType.RATE, false), 404, "NOT_FOUND");
    }
    @Test void eligibleFixIsAllowed() { requireFixTarget(1, 1, ChangeType.RATE, false); }
    @Test void overlapIncludesOneSharedDayButNotAdjacentPeriods() {
        assertThat(overlaps(period("2026-10-01", "2026-10-16"), period("2026-10-16", "2026-10-31"))).isTrue();
        assertThat(overlaps(period("2026-10-01", "2026-10-15"), period("2026-10-16", "2026-10-31"))).isFalse();
    }
    @Test void concurrencyTokenPreservesMicrosecondsInJson() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var token = LocalDateTime.parse("2026-10-14T12:00:00.123456");
        var roundTrip = json.readValue(json.writeValueAsBytes(token), LocalDateTime.class);
        compareLockedTimestamp(roundTrip, token);
        error(() -> compareLockedTimestamp(token.withNano(123000000), token), 409, "STALE_ASSIGNMENT");
    }
    @ParameterizedTest @CsvSource({
        "listAssignments,UI-12.read", "createAssignment,UI-12.create", "checkAssignments,UI-12.update",
        "saveAssignmentMonths,UI-12.update", "createAssignmentFix,UI-12.update",
        "saveAssignmentCancellation,UI-12.delete", "saveUploadApplication,UI-12.upload",
        "getAssignmentsExport,UI-12.download", "getUploadTemplate,UI-12.download"
    })
    void publicActionPlacementUsesAssignmentKeys(String operation, String key) {
        assertThat(AssignmentActions.key(operation)).isEqualTo(key);
        assertThat(AssignmentActions.can(new PermissionService(), operation)).isTrue();
        assertThat(new CurrentUser().userId()).isNull();
        assertThat(ProjectActions.key("listProjectAssignments")).isEqualTo("UI-01.read");
    }
}
