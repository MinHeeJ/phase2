package com.sicost.us3;

import com.sicost.apiops.ProjectBoardModels.OperatingParam;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.DispatchExpiryCalculator;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class DispatchExpiryRules {
    private DispatchExpiryRules() {}

    public record Parameters(int months, List<Integer> alertDays) {
        public Parameters {
            alertDays = alertDays.stream().distinct().sorted(Comparator.reverseOrder()).toList();
            if (months < 1 || alertDays.size() < 2 || alertDays.stream().anyMatch(d -> d < 0)) {
                throw invalidParameters();
            }
        }
        public int warningDay() { return alertDays.get(0); }
        public int dangerDay() { return alertDays.get(alertDays.size() - 1); }
    }

    public enum Kind { DASH, DATE, COUNTDOWN, OVERRUN }

    public record Display(LocalDate dispatch_first_dt, LocalDate dispatch_expiry_dt,
            String dispatch_display, String dispatch_tone, List<LocalDate> dispatch_alert_dates,
            Kind kind) {
        public boolean within60() { return kind == Kind.COUNTDOWN || kind == Kind.OVERRUN; }
    }

    public static Parameters parameters(List<OperatingParam> rows) {
        try {
            var months = rows.stream().filter(r -> "DISPATCH_MAX_MONTHS".equals(r.oprt_std_id()))
                .findFirst().orElseThrow();
            var alerts = rows.stream().filter(r -> "DISPATCH_ALERT_DAYS".equals(r.oprt_std_id()))
                .findFirst().orElseThrow();
            var days = Arrays.stream(alerts.txt_val().split(",", -1)).map(String::trim)
                .map(Integer::valueOf).toList();
            return new Parameters(months.num_val().intValueExact(), days);
        } catch (ArithmeticException | IllegalArgumentException | NoSuchElementException | NullPointerException ex) {
            throw invalidParameters();
        }
    }

    private static ApiException invalidParameters() {
        return ApiException.badRequest("파견 기간·알림 기준값을 확인하세요.", "month");
    }

    public static boolean dispatchWithin60(Map<String, String> query) {
        String value = query.get("dispatchWithin60");
        if (value == null || "false".equals(value)) return false;
        if ("true".equals(value)) return true;
        throw ApiException.badRequest("파견 만료 조회 조건을 확인하세요.", "dispatchWithin60");
    }

    public static Display display(String contract, LocalDate first, LocalDate end,
            LocalDate today, Parameters parameters) {
        if (!"DP".equals(contract)) {
            return new Display(null, null, "–", "neutral", List.of(), Kind.DASH);
        }
        if (first == null) throw ApiException.badRequest("최초 투입일을 확인하세요.", "bgn_dt");
        LocalDate expiry = DispatchExpiryCalculator.expiry(first, parameters.months());
        var alerts = parameters.alertDays().stream().map(expiry::minusDays).toList();
        if (end.isBefore(today)) return date(first, expiry, alerts);
        if (end.isAfter(expiry)) {
            return new Display(first, expiry, "파견 기간 초과", "danger", alerts, Kind.OVERRUN);
        }
        long days = ChronoUnit.DAYS.between(today, expiry);
        if (days <= parameters.warningDay()) {
            return new Display(first, expiry, "파견 만료 D-" + days,
                days <= parameters.dangerDay() ? "danger" : "warning", alerts, Kind.COUNTDOWN);
        }
        return date(first, expiry, alerts);
    }

    private static Display date(LocalDate first, LocalDate expiry, List<LocalDate> alerts) {
        return new Display(first, expiry, expiry.toString(), "neutral", alerts, Kind.DATE);
    }
}
