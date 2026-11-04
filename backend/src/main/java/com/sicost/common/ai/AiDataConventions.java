package com.sicost.common.ai;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.Sensitive;
import com.sicost.common.validation.CalendarValues;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Set;

public final class AiDataConventions {
    private AiDataConventions() {}
    public static BigDecimal won(BigDecimal value) { return value == null ? null : value.setScale(0, RoundingMode.HALF_UP); }
    public static BigDecimal twoDecimals(BigDecimal value) { return value == null ? null : value.setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal ratioPercent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
    }
    public static YearMonth month(String value, String field) { return CalendarValues.month(value, field); }
    public static YearMonth currentMonth(Clock clock) { return YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    public static String requestType(String value) {
        if (value == null || !Set.of("PROJECT", "OPP", "TEXT", "NEXT").contains(value))
            throw ApiException.badRequest("추천 요청 유형을 확인하세요.", "direction");
        return value;
    }
    public static String aDecisionToWire(String value) {
        return switch (value) {
            case "NONE" -> "PENDING";
            case "ADOPT", "REJECT" -> value;
            default -> throw new IllegalArgumentException("기존 추천 결정 코드가 아닙니다.");
        };
    }
    public static String forecastCode(String wire) {
        if (wire == null) throw ApiException.badRequest("예측 유형을 확인하세요.", "forecastType");
        return switch (wire) {
            case "OPERATING_PROFIT" -> "OP_PROFIT";
            case "REVENUE", "STAFF", "CASH" -> wire;
            default -> throw ApiException.badRequest("예측 유형을 확인하세요.", "forecastType");
        };
    }
    public static boolean vatIncluded(String metric) {
        return switch (metric) {
            case "CASH" -> true;
            case "REVENUE", "COST" -> false;
            default -> throw new IllegalArgumentException("금액 구분을 확인하세요.");
        };
    }
    public static String closeLabel(String status) {
        return switch (status) {
            case "CLOSED" -> "확정";
            case "OPEN" -> "잠정";
            default -> "판정 불가";
        };
    }
    public record VirtualInput(@Sensitive BigDecimal revenue, @Sensitive BigDecimal cost, @Sensitive BigDecimal costChangePercent) {
        @Override public String toString() { return "VirtualInput[redacted]"; }
    }
    public record VirtualResult(@Sensitive BigDecimal cost, @Sensitive BigDecimal profit, @Sensitive BigDecimal marginPercent) {
        @Override public String toString() { return "VirtualResult[redacted]"; }
    }
    /** Pure, independent virtual arithmetic; callers must enforce AiSensitiveGuard before joining any source. */
    public static VirtualResult simulate(VirtualInput input) {
        if (input == null) throw ApiException.badRequest("가상 조건을 입력하세요.", "virtualCostAssumption");
        requireWon(input.revenue(), "revenue"); requireWon(input.cost(), "cost");
        BigDecimal change = input.costChangePercent();
        if (change == null || change.compareTo(new BigDecimal("-50")) < 0 || change.compareTo(new BigDecimal("100")) > 0 ||
                change.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest("원가 변화율은 -50부터 100까지 소수 둘째 자리로 입력하세요.", "costChangePercent");
        }
        BigDecimal cost = won(input.cost().multiply(BigDecimal.ONE.add(change.movePointLeft(2))));
        BigDecimal profit = input.revenue().subtract(cost);
        return new VirtualResult(cost, profit, ratioPercent(profit, input.revenue()));
    }
    private static void requireWon(BigDecimal amount, String field) {
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0)
            throw ApiException.badRequest("0 이상의 원 단위 금액을 입력하세요.", field);
    }
}
