package com.sicost.us1;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Calculations shared by withdrawal-list projections. */
public final class WithdrawalRules {
    private WithdrawalRules() { }

    public record Assignment(long id, long developerId, long projectId, String projectName,
            LocalDate start, LocalDate end, String status, String extensionChance) { }
    public record History(String type, String fixed) { }

    public static boolean eligible(Assignment assignment) {
        return "ACTIVE".equals(assignment.status()) || "REPLACED".equals(assignment.status());
    }

    public static LocalDate soonEnd(LocalDate today) {
        return YearMonth.from(today).plusMonths(2).atEndOfMonth();
    }

    public static boolean inPeriod(LocalDate end, LocalDate today, int year, String period) {
        if ("SOON".equals(period)) return !end.isBefore(today) && !end.isAfter(soonEnd(today));
        if (end.getYear() != year) return false;
        if ("LATER".equals(period)) return end.isAfter(soonEnd(today));
        if ("DONE".equals(period)) return end.isBefore(today);
        return true;
    }

    public static long remainingDays(LocalDate end, LocalDate today) {
        return ChronoUnit.DAYS.between(today, end);
    }

    public static long extensionCount(List<History> history) {
        return history.stream().filter(h -> "EXTEND".equals(h.type()) && "N".equals(h.fixed())).count();
    }

    public static boolean hasEarlyEnd(List<History> history) {
        return history.stream().anyMatch(h -> "EARLY_END".equals(h.type()) && "N".equals(h.fixed()));
    }

    public static Assignment nextAssignment(Assignment current, List<Assignment> candidates) {
        return candidates.stream().filter(a -> a.id() != current.id()
                && a.developerId() == current.developerId()
                && !"CANCELLED".equals(a.status()) && a.end().isAfter(current.end()))
            .min(Comparator.comparing(Assignment::start).thenComparingLong(Assignment::id)).orElse(null);
    }

    public static long unassignedDevelopers(List<Assignment> assignments, LocalDate today) {
        return assignments.stream().filter(WithdrawalRules::eligible)
            .filter(a -> inPeriod(a.end(), today, today.getYear(), "SOON"))
            .filter(a -> !Objects.equals("H", a.extensionChance()))
            .filter(a -> nextAssignment(a, assignments) == null)
            .map(Assignment::developerId).distinct().count();
    }

    public static LocalDate dispatchExpiry(LocalDate firstStart, int months) {
        LocalDate shifted = firstStart.plusMonths(months);
        if (firstStart.getMonthValue() == 2 && firstStart.getDayOfMonth() == 29
                && shifted.getDayOfMonth() == 28) return shifted;
        return shifted.minusDays(1);
    }

    public static boolean dispatchSoon(LocalDate end, LocalDate expiry, LocalDate today, int alertDays) {
        if (end.isBefore(today)) return false;
        return end.isAfter(expiry) || ChronoUnit.DAYS.between(today, expiry) <= alertDays;
    }
}
