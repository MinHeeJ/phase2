package com.sicost.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

public final class BillingValues {
    private BillingValues() {}

    public static ApiException invalid(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "입력값과 금액 범위를 확인하세요.", field);
    }

    public static long money(BigDecimal value, long minimum, String field) {
        try {
            return ProjectValues.money(value, minimum, field);
        } catch (ApiException ex) {
            throw invalid(field);
        }
    }

    public static long money(JsonNode value, long minimum, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid(field);
        return money(value.decimalValue(), minimum, field);
    }

    public static YearMonth month(String value, String field) {
        try {
            return CalendarValues.month(value, field);
        } catch (ApiException ex) {
            throw invalid(field);
        }
    }

    public static LocalDate date(String value, String field) {
        try {
            return CalendarValues.date(value, field);
        } catch (ApiException ex) {
            throw invalid(field);
        }
    }

    public static long floorWon(BigDecimal value, String field) {
        if (value == null) throw invalid(field);
        return money(value.setScale(0, RoundingMode.FLOOR), Long.MIN_VALUE, field);
    }

    // effectiveRate is the latest DB VAT_RATE at/before the applicable month; null means no row.
    public static long vat(long supply, BigDecimal effectiveRate, Long manualVat) {
        if (manualVat != null) return manualVat;
        BigDecimal rate = effectiveRate == null ? BigDecimal.TEN : effectiveRate;
        if (rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0) throw invalid("VAT_RATE");
        return floorWon(BigDecimal.valueOf(supply).multiply(rate).movePointLeft(2), "vat_amt");
    }

    public static long add(long left, long right, String field) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            throw invalid(field);
        }
    }

    public static int year(Integer requested, Clock clock) {
        int year = requested == null ? ProjectValues.today(clock).getYear() : requested;
        if (year < 1 || year > 9999) throw invalid("year");
        return year;
    }

    public static YearMonth defaultMonth(String lastClosedMonth, Clock clock) {
        return lastClosedMonth == null ? YearMonth.from(ProjectValues.today(clock)) : month(lastClosedMonth, "month");
    }
}
