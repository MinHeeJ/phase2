package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class AssignmentRules {
    private AssignmentRules() {}

    public enum ChangeType { START, EXTEND, EARLY_END, RATE, EXT_CHANCE, PERIOD, REPLACE, FIX }
    public enum Mutation { MONTHS, RATE, FIX, PERIOD, EXTEND, EARLY_END, EXT_CHANCE, REPLACE, CANCEL }
    public record Period(LocalDate start, LocalDate end, BigDecimal rate) {
        public Period {
            if (start == null) throw invalid("bgn_dt");
            if (end == null || end.isBefore(start)) throw invalid("end_dt");
            rate = ProjectValues.decimal(rate, 5, "asgn_rt");
            if (rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0) throw invalid("asgn_rt");
        }
    }
    public record Month(YearMonth month, BigDecimal automatic, BigDecimal quantity, boolean manual) {}
    public record Rate(LocalDate effectiveDate, Long amount) {}
    public record Pricing(Long amount, String storageBasis, String wireBasis) {}
    public record Reason(String code, String description, boolean active) {}

    private static ApiException invalid(String field) {
        return ApiException.badRequest("입력값을 확인하세요.", field);
    }

    public static String allocated(long id) {
        if (id < 1) throw invalid("asgn_id");
        return "A-" + String.format(Locale.ROOT, "%04d", id);
    }

    public static BigDecimal automaticMm(Period period, YearMonth month) {
        LocalDate start = period.start().isAfter(month.atDay(1)) ? period.start() : month.atDay(1);
        LocalDate end = period.end().isBefore(month.atEndOfMonth()) ? period.end() : month.atEndOfMonth();
        if (end.isBefore(start)) return BigDecimal.ZERO.setScale(2);
        return BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end) + 1)
            .multiply(period.rate()).divide(BigDecimal.valueOf(month.lengthOfMonth()), 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal manualMm(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) throw invalid("mm_qty");
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // The caller persists only open rows; closed rows, including out-of-period ones, are returned unchanged.
    public static List<Month> rebuildMonths(Period period, List<Month> existing, Set<YearMonth> closed) {
        Map<YearMonth, Month> result = new TreeMap<>();
        Map<YearMonth, Month> previous = new HashMap<>();
        for (Month row : existing) {
            previous.put(row.month(), row);
            if (closed.contains(row.month())) result.put(row.month(), row);
        }
        YearMonth last = YearMonth.from(period.end());
        for (YearMonth month = YearMonth.from(period.start()); !month.isAfter(last); month = month.plusMonths(1)) {
            if (closed.contains(month)) continue;
            Month old = previous.get(month);
            BigDecimal automatic = automaticMm(period, month);
            boolean manual = old != null && old.manual();
            result.put(month, new Month(month, automatic, manual ? old.quantity() : automatic, manual));
        }
        return List.copyOf(result.values());
    }

    public static Month editedMonth(Month current, BigDecimal value, Set<YearMonth> closed) {
        if (closed.contains(current.month())) {
            throw ApiException.conflict("CLOSED_MONTH", "마감된 달의 MM은 바꿀 수 없습니다.", "work_ym");
        }
        BigDecimal quantity = manualMm(value);
        return new Month(current.month(), current.automatic(), quantity,
            quantity.compareTo(current.automatic()) != 0);
    }

    public static LocalDate financialDate(LocalDate requested, Period period, Set<YearMonth> closed) {
        if (requested == null) throw invalid("aply_dt");
        LocalDate effective = requested.isBefore(period.start()) ? period.start() : requested;
        while (!effective.isAfter(period.end()) && closed.contains(YearMonth.from(effective))) {
            effective = YearMonth.from(effective).plusMonths(1).atDay(1);
        }
        return effective.isAfter(period.end()) ? null : effective;
    }

    public static List<Rate> inheritedRates(List<Rate> original, LocalDate replacement, LocalDate originalEnd) {
        if (replacement == null || originalEnd == null || replacement.isAfter(originalEnd)) throw invalid("aply_dt");
        var result = new ArrayList<Rate>();
        Rate atReplacement = original.stream().filter(r -> !r.effectiveDate().isAfter(replacement))
            .max(Comparator.comparing(Rate::effectiveDate)).orElse(null);
        if (atReplacement != null) result.add(new Rate(replacement, atReplacement.amount()));
        original.stream().filter(r -> r.effectiveDate().isAfter(replacement)
            && !r.effectiveDate().isAfter(originalEnd)).sorted(Comparator.comparing(Rate::effectiveDate))
            .forEach(result::add);
        return List.copyOf(result);
    }

    public static Pricing pricing(String contractType, Long price, String basis) {
        if ("TK".equals(contractType) || "IN".equals(contractType)) return new Pricing(null, "MONTH", null);
        if (!"MO".equals(contractType) && !"DP".equals(contractType)) throw invalid("cntrct_ty_cd");
        if (price == null || price <= 0) throw invalid("bill_untprc_amt");
        if (basis == null || !Set.of("MONTH", "DAY", "HOUR").contains(basis)) throw invalid("untprc_base_cd");
        return new Pricing(price, basis, basis);
    }

    public static boolean retroactive(LocalDate application, Clock clock) {
        if (application == null) throw invalid("aply_dt");
        return application.isBefore(ProjectValues.today(clock));
    }

    public static boolean reasonRequired(ChangeType type, LocalDate application, Clock clock, YearMonth lastClosed) {
        return type == ChangeType.EARLY_END || type == ChangeType.REPLACE || type == ChangeType.FIX
            || retroactive(application, clock)
            || (lastClosed != null && !YearMonth.from(application).isAfter(lastClosed));
    }

    // active is obtained from cmn.cd_dtl, never from a fixed list of seed codes.
    public static void requireReason(ChangeType type, LocalDate application, Clock clock,
            YearMonth lastClosed, Reason reason) {
        boolean missing = reason == null || reason.code() == null || reason.code().isBlank();
        if (reasonRequired(type, application, clock, lastClosed) && missing) throw invalid("rsn_cd");
        if (!missing && !reason.active()) throw invalid("rsn_cd");
        if (reason != null && reason.description() != null && reason.description().length() > 200) {
            throw invalid("rsn_desc");
        }
    }

    public static String historyReason(Long uploadId, String description) {
        if (description != null && description.length() > 200) throw invalid("rsn_desc");
        if (uploadId == null) return description;
        if (uploadId < 1) throw invalid("upld_id");
        return "엑셀 업로드 #" + uploadId + (description == null || description.isBlank() ? "" : " · " + description);
    }

    public static void requireProjectWritable(String useYn, String projectStatus) {
        if (!"Y".equals(useYn) || !Set.of("PLAN", "RUN", "HOLD").contains(Objects.toString(projectStatus, ""))) {
            throw ApiException.conflict("PROJECT_CLOSED", "입력할 수 없는 프로젝트입니다.", null);
        }
    }

    public static void requireMutation(String status, Mutation mutation, LocalDate effective, LocalDate end) {
        boolean replacedAllowed = mutation == Mutation.MONTHS || mutation == Mutation.RATE || mutation == Mutation.FIX;
        if ("CANCELLED".equals(status) || ("REPLACED".equals(status) && !replacedAllowed)) {
            throw ApiException.conflict("ASSIGNMENT_LOCKED", "변경할 수 없는 투입 상태입니다.", null);
        }
        if ("REPLACED".equals(status) && mutation != Mutation.FIX
                && (effective == null || end == null || effective.isAfter(end))) throw invalid("aply_dt");
    }

    public static void requireFixTarget(long assignment, long owner, ChangeType type, boolean fixed) {
        if (assignment != owner) throw ApiException.notFound("변경 이력이 없습니다.");
        if (type == ChangeType.START || type == ChangeType.FIX || fixed) {
            throw ApiException.conflict("NOT_FIXABLE", "정정할 수 없는 이력입니다.", null);
        }
    }

    public static boolean overlaps(Period left, Period right) {
        return !left.start().isAfter(right.end()) && !right.start().isAfter(left.end());
    }

    // Must be called after SELECT FOR UPDATE using coalesce(updated_at, created_at) from that same row.
    public static void compareLockedTimestamp(LocalDateTime expected, LocalDateTime actual) {
        if (expected == null) throw invalid("updated_at");
        if (!expected.equals(actual)) throw stale();
    }

    public static ApiException stale() {
        return ApiException.conflict("STALE_ASSIGNMENT", "다른 사람이 먼저 바꿨습니다. 창을 다시 열어 주세요.", "updated_at");
    }
}
