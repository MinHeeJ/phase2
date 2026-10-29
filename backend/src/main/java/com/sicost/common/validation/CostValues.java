package com.sicost.common.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.IntStream;

public final class CostValues {
    private CostValues() {}

    public static YearMonth month(String value, Clock clock) {
        YearMonth month = BillingValues.month(value, "month");
        if (month.isAfter(YearMonth.from(ProjectValues.today(clock)))) throw BillingValues.invalid("month");
        return month;
    }

    public static int year(Integer value, Clock clock) {
        if (value == null || value < 1 || value > ProjectValues.today(clock).getYear()) {
            throw BillingValues.invalid("year");
        }
        return value;
    }

    public static List<String> months(Integer value, Clock clock) {
        int year = year(value, clock);
        int end = year == ProjectValues.today(clock).getYear() ? ProjectValues.today(clock).getMonthValue() : 12;
        return IntStream.rangeClosed(1, end).mapToObj(m -> YearMonth.of(year, m).toString()).toList();
    }

    public static Long optionalId(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            if (!value.matches("[0-9]{1,19}")) throw BillingValues.invalid(field);
            return id(Long.parseLong(value), field);
        } catch (NumberFormatException ex) {
            throw BillingValues.invalid(field);
        }
    }

    public static long id(long value, String field) {
        if (value <= 0) throw BillingValues.invalid(field);
        return value;
    }

    public static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0 || numerator == null) return null;
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }

    public static long signedWon(BigDecimal value) {
        try {
            return value.setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (ArithmeticException ex) {
            throw BillingValues.invalid("amount");
        }
    }
}
