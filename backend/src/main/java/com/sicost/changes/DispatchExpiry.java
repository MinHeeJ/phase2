package com.sicost.changes;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Shared dispatch expiry rule for assignment changes and withdrawal lists. */
public final class DispatchExpiry {
    private DispatchExpiry() {}

    public enum Display { NONE, DATE, WARNING, DANGER, EXCEEDED }

    public record Result(LocalDate firstStart, LocalDate expiry, Long daysRemaining,
            LocalDate alertDate, LocalDate urgentDate, Display display) {
        public boolean dispatchSoon() {
            return display == Display.WARNING || display == Display.DANGER || display == Display.EXCEEDED;
        }
    }

    public static LocalDate expiry(LocalDate firstStart, int months) {
        LocalDate anniversary = firstStart.plusMonths(months);
        if (firstStart.getMonthValue() == 2 && firstStart.getDayOfMonth() == 29
                && anniversary.getMonthValue() == 2 && anniversary.getDayOfMonth() == 28) {
            return anniversary;
        }
        return anniversary.minusDays(1);
    }

    public static Result evaluate(String contractType, LocalDate firstStart, LocalDate end,
            LocalDate today, int months, int alertDays, int urgentDays) {
        if (!"DP".equals(contractType) || firstStart == null) {
            return new Result(null, null, null, null, null, Display.NONE);
        }
        LocalDate expiry = expiry(firstStart, months);
        long days = ChronoUnit.DAYS.between(today, expiry);
        int outer = Math.max(alertDays, urgentDays);
        int inner = Math.min(alertDays, urgentDays);
        Display display;
        if (end.isBefore(today)) display = Display.DATE;
        else if (end.isAfter(expiry)) display = Display.EXCEEDED;
        else if (days <= inner) display = Display.DANGER;
        else if (days <= outer) display = Display.WARNING;
        else display = Display.DATE;
        return new Result(firstStart, expiry, days, expiry.minusDays(outer), expiry.minusDays(inner), display);
    }
}
