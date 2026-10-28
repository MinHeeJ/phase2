package com.sicost.common.validation;

import java.time.LocalDate;
import java.util.Objects;

public final class DispatchExpiryCalculator {
    private DispatchExpiryCalculator() {}

    public static LocalDate expiry(LocalDate first, int months) {
        Objects.requireNonNull(first, "first");
        if (months < 1) throw new IllegalArgumentException("파견 최대 개월은 양수여야 합니다.");
        LocalDate expiry = first.minusDays(1).plusMonths(months);
        LocalDate anniversary = first.plusMonths(months);
        // The approved leap-day date is stricter than Java's literal month adjustment.
        if (first.getMonthValue() == 2 && first.getDayOfMonth() == 29
                && anniversary.getMonthValue() == 2 && anniversary.getDayOfMonth() == 28) {
            return expiry.minusDays(1);
        }
        return expiry;
    }

    public static boolean exceeds(LocalDate first, int months, LocalDate end) {
        return end.isAfter(expiry(first, months));
    }
}
