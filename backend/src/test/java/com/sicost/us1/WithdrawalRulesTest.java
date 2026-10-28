package com.sicost.us1;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithdrawalRulesTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-10-14");

    private WithdrawalRules.Assignment assignment(long id, long developer, String start, String end,
            String status, String chance) {
        return new WithdrawalRules.Assignment(id, developer, 1, "프로젝트",
            LocalDate.parse(start), LocalDate.parse(end), status, chance);
    }

    @Test void soonIncludesTodayAndCrossesYearBoundary() {
        var yearEnd = LocalDate.parse("2026-12-15");
        assertThat(WithdrawalRules.inPeriod(LocalDate.parse("2027-02-28"), yearEnd, 2026, "SOON")).isTrue();
        assertThat(WithdrawalRules.inPeriod(LocalDate.parse("2027-03-01"), yearEnd, 2026, "SOON")).isFalse();
        assertThat(WithdrawalRules.inPeriod(TODAY, TODAY, 2026, "SOON")).isTrue();
        assertThat(WithdrawalRules.remainingDays(TODAY, TODAY)).isZero();
    }

    @Test void fixedHistoryDoesNotCount() {
        var history = List.of(new WithdrawalRules.History("EXTEND", "N"),
            new WithdrawalRules.History("EXTEND", "Y"), new WithdrawalRules.History("EARLY_END", "Y"));
        assertThat(WithdrawalRules.extensionCount(history)).isEqualTo(1);
        assertThat(WithdrawalRules.hasEarlyEnd(history)).isFalse();
    }

    @Test void nextAssignmentBreaksTiesByIdAndExcludesCancelled() {
        var current = assignment(1, 7, "2026-01-01", "2026-12-01", "ACTIVE", "M");
        var later = assignment(3, 7, "2026-11-01", "2027-02-01", "ACTIVE", null);
        var tie = assignment(2, 7, "2026-11-01", "2027-01-01", "REPLACED", null);
        var cancelled = assignment(4, 7, "2026-10-01", "2027-01-01", "CANCELLED", null);
        assertThat(WithdrawalRules.nextAssignment(current, List.of(current, later, tie, cancelled))).isEqualTo(tie);
    }

    @Test void summaryCountsPeopleNotRows() {
        var first = assignment(1, 7, "2026-01-01", "2026-12-01", "ACTIVE", "M");
        var second = assignment(2, 7, "2026-02-01", "2026-12-01", "REPLACED", null);
        assertThat(WithdrawalRules.unassignedDevelopers(List.of(first, second), TODAY)).isEqualTo(1);
    }

    @Test void leapExpiryAndCompletedDispatchPriority() {
        assertThat(WithdrawalRules.dispatchExpiry(LocalDate.parse("2024-02-29"), 24))
            .isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(WithdrawalRules.dispatchExpiry(LocalDate.parse("2024-11-20"), 24))
            .isEqualTo(LocalDate.parse("2026-11-19"));
        assertThat(WithdrawalRules.dispatchSoon(TODAY.minusDays(1), TODAY.minusDays(30), TODAY, 60)).isFalse();
        assertThat(WithdrawalRules.dispatchSoon(TODAY, TODAY.minusDays(1), TODAY, 60)).isTrue();
    }
}
