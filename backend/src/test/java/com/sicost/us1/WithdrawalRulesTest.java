package com.sicost.us1;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.us1.WithdrawalExamples.*;
import static com.sicost.us1.WithdrawalModels.*;

class WithdrawalRulesTest {
    Query query(String period, int year) {
        var map = new HashMap<String, String>();
        map.put("year", Integer.toString(year));
        if (period != null) map.put("period", period);
        return WithdrawalInput.parse(map, false);
    }
    Row row(long id, long dev, String end, String ext, Long next) {
        return WithdrawalRules.calculate(source(id, dev, end, ext, next), 2026, TODAY, STANDARD);
    }
    @ParameterizedTest @CsvSource({"2026-11-20,37", "2026-10-14,0", "2026-10-13,-1", "2026-12-31,78"})
    void remainingDaysUseCalendarDates(String end, long expected) {
        assertThat(row(1, 1, end, null, null).remaining_days()).isEqualTo(expected);
    }
    @Test void soonIncludesTodayAndMonthEndButNotYesterdayOrFollowingMonth() {
        assertThat(WithdrawalRules.soon(TODAY, TODAY)).isTrue();
        assertThat(WithdrawalRules.soon(LocalDate.of(2026, 12, 31), TODAY)).isTrue();
        assertThat(WithdrawalRules.soon(TODAY.minusDays(1), TODAY)).isFalse();
        assertThat(WithdrawalRules.soon(LocalDate.of(2027, 1, 1), TODAY)).isFalse();
    }
    @Test void yearEndSoonIgnoresSelectedYearAndEndsOnFebruaryLastDay() {
        var today = LocalDate.of(2026, 12, 20);
        var feb = row(1, 1, "2027-02-28", null, null);
        assertThat(WithdrawalRules.matches(feb, query("SOON", 2025), today)).isTrue();
        assertThat(WithdrawalRules.matches(row(2, 2, "2027-03-01", null, null), query("SOON", 2026), today)).isFalse();
        assertThat(WithdrawalRules.matches(feb, query(null, 2026), today)).isFalse();
    }
    @Test void defaultLaterAndDoneIntersectEndDateYear() {
        assertThat(WithdrawalRules.matches(row(1, 1, "2026-10-13", null, null), query("DONE", 2026), TODAY)).isTrue();
        assertThat(WithdrawalRules.matches(row(1, 1, "2025-10-13", null, null), query("DONE", 2026), TODAY)).isFalse();
        assertThat(WithdrawalRules.matches(row(1, 1, "2027-01-05", null, null), query("LATER", 2026), TODAY)).isFalse();
        assertThat(WithdrawalRules.matches(row(1, 1, "2027-01-05", null, null), query("LATER", 2027), TODAY)).isTrue();
    }
    @Test void summaryDeduplicatesPeopleButIncludesAnyEligibleNonHighRow() {
        var rows = List.of(row(1, 1, "2026-11-20", "H", null), row(2, 2, "2026-11-20", "M", null),
            row(3, 3, "2026-11-20", null, 9L), row(4, 2, "2026-11-21", "H", null),
            row(5, 2, "2026-11-22", null, null), row(6, 4, "2027-01-01", null, null));
        assertThat(WithdrawalRules.summary(rows, TODAY)).isEqualTo(new Summary(3, 1));
    }
    @Test void nextAssignmentUsesWithdrawalDateAndEndedRowsHideNext() {
        assertThat(row(1, 1, "2026-11-20", null, 2L).next_display()).isEqualTo("다음 프로젝트 · 2026-12-01");
        assertThat(row(1, 1, "2026-12-31", null, 2L).next_display()).isEqualTo("다음 프로젝트 · 진행 중");
        assertThat(row(1, 1, "2026-11-20", null, null).next_display()).isEqualTo("없음");
        assertThat(row(1, 1, "2026-10-13", null, 2L).next_display()).isEqualTo("–");
    }
    @Test void nullChanceMeansNoneAndValidUnmatchedJobMeansEmpty() {
        var r = row(1, 1, "2026-11-20", null, null);
        assertThat(WithdrawalRules.matches(r, WithdrawalInput.parse(Map.of("year", "2026", "ext", "NONE"), false), TODAY)).isTrue();
        assertThat(WithdrawalRules.matches(r, WithdrawalInput.parse(Map.of("year", "2026", "job", "NO_SUCH_JOB"), false), TODAY)).isFalse();
    }
    @Test void deterministicFinalTiebreakIsAssignmentId() {
        var rows = new ArrayList<>(List.of(row(9, 1, "2026-11-20", null, null), row(2, 1, "2026-11-20", null, null)));
        rows.sort(WithdrawalRules.order("end_dt,asc;dev_nm,asc"));
        assertThat(rows).extracting(r -> r.source().asgn_id()).containsExactly(2L, 9L);
    }
    @Test void historicalYearAndFutureStartExposeLockReason() {
        var s = source(1, 1, "2026-11-20", null, null);
        assertThat(WithdrawalRules.calculate(s, 2025, TODAY, STANDARD).chance_lock_reason()).contains("읽기 전용");
        assertThat(WithdrawalRules.calculate(s, 2026, LocalDate.of(2025, 12, 1), STANDARD).chance_lock_reason()).contains("투입일");
    }
}
