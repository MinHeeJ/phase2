package com.sicost.common.billing;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.CalendarValues;
import com.sicost.common.validation.ProjectValues;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Set;

public final class BillingRules {
    private BillingRules() {}

    public static ApiException invalid(String field) {
        return new ApiException(400, "VALIDATION_ERROR", "입력값의 형식과 범위를 확인하세요.", field);
    }

    public static void billable(String type) {
        if (type == null || !Set.of("TK", "MO", "DP").contains(type)) {
            throw invalid("project_id");
        }
    }

    public static YearMonth month(String value, String field) {
        try { return CalendarValues.month(value, field); }
        catch (ApiException ex) { throw invalid(field); }
    }

    public static YearMonth revenueMonth(String type, String attribution, LocalDate billDate) {
        billable(type);
        if (!"TK".equals(type)) return month(attribution, "month");
        if (billDate == null) throw invalid("bill_dt");
        return YearMonth.from(billDate);
    }

    public static YearMonth effectiveMonth(String type, String attribution, LocalDate billDate,
            LocalDate plannedDate, LocalDateTime createdAt) {
        billable(type);
        if (!"TK".equals(type) || billDate != null) return revenueMonth(type, attribution, billDate);
        if (plannedDate != null) return YearMonth.from(plannedDate);
        if (createdAt == null) throw invalid("bill_dt");
        return YearMonth.from(createdAt);
    }

    public static void requireOpen(YearMonth month, String status, String field) {
        if (month == null) throw invalid(field);
        if ("CLOSED".equals(status)) {
            throw ApiException.conflict("CLOSED_MONTH", month + "은 마감된 달입니다.", field);
        }
    }

    public static boolean pastYear(int year, Clock clock) {
        if (year < 1 || year > 9999) throw invalid("year");
        return year < ProjectValues.today(clock).getYear();
    }

    public static void requireDraft(String state) {
        if (!"DRAFT".equals(state)) throw stale();
    }

    public static void requireAffected(int rows) {
        if (rows != 1) throw stale();
    }

    public static ApiException stale() {
        return ApiException.conflict("STALE_BILL", "청구 상태가 변경되었습니다. 다시 조회하세요.", null);
    }

    public static long won(BigDecimal value, String field) {
        try {
            if (value != null) return value.longValueExact();
        } catch (ArithmeticException ex) { throw invalid(field); }
        throw invalid(field);
    }

    public static long add(long left, long right, String field) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { throw invalid(field); }
    }

    public static long subtract(long left, long right, String field) {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException ex) { throw invalid(field); }
    }

    public static BigDecimal quantity(BigDecimal value, int precision, int scale, String field) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > scale
                || value.compareTo(BigDecimal.TEN.pow(precision - scale)) >= 0) throw invalid(field);
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    public static String text(String value, int maximum, String field) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) throw invalid(field);
        return value;
    }

    public static String numberPrefix(Clock clock) {
        LocalDate today = ProjectValues.today(clock);
        return String.format(java.util.Locale.ROOT, "B-%02d%02d-", today.getYear() % 100, today.getMonthValue());
    }

    public static String number(String prefix, long sequence) {
        if (prefix == null || !prefix.matches("B-[0-9]{4}-") || sequence < 1) throw invalid("bill_no");
        return text(prefix + String.format(java.util.Locale.ROOT, "%02d", sequence), 20, "bill_no");
    }
}
