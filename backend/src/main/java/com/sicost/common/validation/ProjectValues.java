package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;

public final class ProjectValues {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private ProjectValues() {}

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }

    public static String settlement(String type, String requested) {
        if (type == null || !Set.of("TK", "MO", "DP", "IN").contains(type)) {
            throw ApiException.badRequest("계약유형을 확인하세요.", "cntrct_ty_cd");
        }
        if (type.equals("IN")) return null;
        if (type.equals("TK")) {
            if (requested != null && !requested.equals("STAGE")) {
                throw ApiException.badRequest("턴키는 단계별 정산입니다.", "stlm_mthd_cd");
            }
            return "STAGE";
        }
        if (requested == null) return "MONTH";
        if (!Set.of("MONTH", "DAY", "HOUR").contains(requested)) {
            throw ApiException.badRequest("정산 방식을 확인하세요.", "stlm_mthd_cd");
        }
        return requested;
    }

    public static long money(BigDecimal value, long minimum, String field) {
        try {
            if (value != null) {
                long amount = value.longValueExact();
                if (amount >= minimum) return amount;
            }
        } catch (ArithmeticException ignored) {
            // Return the same field error for fractional and out-of-range values.
        }
        throw ApiException.badRequest("금액 범위를 확인하세요.", field);
    }

    public static BigDecimal decimal(BigDecimal value, int precision, String field) {
        if (value == null || value.stripTrailingZeros().scale() > 2
                || value.abs().compareTo(BigDecimal.TEN.pow(precision - 2)) >= 0) {
            throw ApiException.badRequest("소수 둘째 자리와 숫자 범위를 확인하세요.", field);
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal percent(BigDecimal value, String field) {
        value = decimal(value, 6, field);
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw ApiException.badRequest("0~100 사이로 입력하세요.", field);
        }
        return value;
    }

    public static BigDecimal defaultMargin(BigDecimal effectiveBudgetRate) {
        return HUNDRED.subtract(percent(effectiveBudgetRate, "BUDGET_RATE"));
    }

    public static long targetCost(long currentAmount, BigDecimal margin) {
        if (currentAmount < 0) throw ApiException.badRequest("금액을 확인하세요.", "cur_cntrct_amt");
        return BigDecimal.valueOf(currentAmount)
            .multiply(HUNDRED.subtract(percent(margin, "trgt_mrgn_rt")))
            .divide(HUNDRED, 0, RoundingMode.FLOOR).longValueExact();
    }

    public static long vat(long amount) {
        if (amount < 0) throw ApiException.badRequest("금액을 확인하세요.", "expns_amt");
        return BigDecimal.valueOf(amount).movePointLeft(1).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    public static PerformancePeriod period(LocalDate contractStart, LocalDate contractEnd,
            LocalDate performanceStart, LocalDate currentEnd) {
        if (contractStart == null) throw ApiException.badRequest("계약 시작일을 입력하세요.", "cntrct_bgn_dt");
        if (contractEnd == null || contractEnd.isBefore(contractStart)) {
            throw ApiException.badRequest("계약 종료일을 확인하세요.", "cntrct_end_dt");
        }
        LocalDate start = performanceStart == null ? contractStart : performanceStart;
        LocalDate end = currentEnd == null ? contractEnd : currentEnd;
        if (start.isBefore(contractStart) || start.isAfter(end)) {
            throw ApiException.badRequest("수행 시작일을 확인하세요.", "prfm_bgn_dt");
        }
        return new PerformancePeriod(start, end);
    }

    public record PerformancePeriod(LocalDate start, LocalDate end) {
        public PerformancePeriod {
            if (start == null || end == null || end.isBefore(start)) {
                throw ApiException.badRequest("수행 기간을 확인하세요.", "prfm_bgn_dt");
            }
        }
        public long days() { return ChronoUnit.DAYS.between(start, end) + 1; }
        public boolean intersects(int year) {
            if (year < 1 || year > 9999) throw ApiException.badRequest("연도를 확인하세요.", "year");
            return !end.isBefore(LocalDate.of(year, 1, 1)) && !start.isAfter(LocalDate.of(year, 12, 31));
        }
        public boolean contains(YearMonth month) {
            return !month.isBefore(YearMonth.from(start)) && !month.isAfter(YearMonth.from(end));
        }
    }

    public static void requirePartMonth(String month, String maximumClosedMonth) {
        YearMonth requested = CalendarValues.month(month, "aply_bgn_ym");
        if (maximumClosedMonth != null
                && !requested.isAfter(CalendarValues.month(maximumClosedMonth, "aply_bgn_ym"))) {
            throw ApiException.conflict("CLOSED_MONTH", "마지막 마감월 다음 달부터 선택하세요.", "aply_bgn_ym");
        }
    }
}
