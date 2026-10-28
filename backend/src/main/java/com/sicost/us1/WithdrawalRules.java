package com.sicost.us1;

import com.sicost.us3.DispatchExpiryRules;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.sicost.us1.WithdrawalModels.*;

public final class WithdrawalRules {
    private WithdrawalRules() {}
    public record DispatchStandard(int months, List<Integer> alertDays) {
        public DispatchStandard {
            if (months < 1 || alertDays == null || alertDays.size() < 2
                    || alertDays.stream().anyMatch(d -> d < 0)) throw WithdrawalInput.error("month");
            alertDays = alertDays.stream().distinct().sorted().toList();
        }
    }
    public static boolean soon(LocalDate end, LocalDate today) {
        return !end.isBefore(today) && !end.isAfter(YearMonth.from(today).plusMonths(2).atEndOfMonth());
    }
    public static Row calculate(Source s, int year, LocalDate today, DispatchStandard standard) {
        long days = ChronoUnit.DAYS.between(today, s.end_dt());
        String next = days < 0 ? "–" : s.next_asgn_id() == null ? "없음"
            : s.next_prj_nm() + " · " + (s.next_bgn_dt().isAfter(s.end_dt()) ? s.next_bgn_dt() : "진행 중");
        String reason = year < today.getYear() ? "지난 해 · 읽기 전용"
            : days < 0 ? "이미 철수한 투입입니다."
            : !"ACTIVE".equals(s.asgn_stts_cd()) ? "교체되거나 취소된 투입입니다."
            : !"Y".equals(s.use_yn()) || Set.of("END", "LOST").contains(s.prj_stts_cd()) ? "종료·실주·사용 안 함 프로젝트입니다."
            : s.bgn_dt().isAfter(today) ? "투입일(" + s.bgn_dt() + ") 전에는 바꿀 수 없습니다." : null;
        var dispatch = DispatchExpiryRules.display(s.cntrct_ty_cd(), s.dispatch_first_dt(),
            s.end_dt(), today, new DispatchExpiryRules.Parameters(standard.months(), standard.alertDays()));
        return new Row(s, days, dispatch.dispatch_expiry_dt(), dispatch.dispatch_display(),
            dispatch.dispatch_tone(), next, reason != null, reason, dispatch.dispatch_alert_dates());
    }
    public static Summary summary(List<Row> rows, LocalDate today) {
        var soon = rows.stream().filter(r -> soon(r.source().end_dt(), today)).toList();
        return new Summary(soon.stream().map(r -> r.source().dev_id()).distinct().count(),
            soon.stream().filter(r -> !"H".equals(r.source().ext_psblty_cd()) && r.source().next_asgn_id() == null)
                .map(r -> r.source().dev_id()).distinct().count());
    }
    public static boolean matches(Row r, Query q, LocalDate today) {
        Source s = r.source();
        if ("SOON".equals(q.period())) { if (!soon(s.end_dt(), today)) return false; }
        else {
            if (s.end_dt().getYear() != q.year()) return false;
            if ("DONE".equals(q.period()) && !s.end_dt().isBefore(today)) return false;
            if ("LATER".equals(q.period()) && !s.end_dt().isAfter(YearMonth.from(today).plusMonths(2).atEndOfMonth())) return false;
        }
        if (q.ext() != null && !("NONE".equals(q.ext()) ? s.ext_psblty_cd() == null : q.ext().equals(s.ext_psblty_cd()))) return false;
        if (q.job() != null && !q.job().equals(s.job_cd())) return false;
        if (q.q() != null && !(s.dev_nm().toLowerCase(Locale.ROOT).contains(q.q().toLowerCase(Locale.ROOT))
                || s.mng_no().toLowerCase(Locale.ROOT).contains(q.q().toLowerCase(Locale.ROOT)))) return false;
        return !q.dispatchWithin60() || Set.of("danger", "warning").contains(r.dispatch_tone());
    }
    public static Comparator<Row> order(String sort) {
        Comparator<Row> result = (a, b) -> 0;
        for (String part : sort.split(";")) {
            String[] pair = part.split(",");
            Comparator<Row> next = comparing(pair[0]);
            result = result.thenComparing("desc".equals(pair[1]) ? next.reversed() : next);
        }
        return result.thenComparingLong(r -> r.source().asgn_id());
    }
    private static Comparator<Row> comparing(String field) {
        return switch (field) {
            case "asgn_id" -> Comparator.comparingLong(r -> r.source().asgn_id());
            case "asgn_no" -> Comparator.comparing(r -> r.source().asgn_no());
            case "dev_nm" -> Comparator.comparing(r -> r.source().dev_nm());
            case "prj_nm" -> Comparator.comparing(r -> r.source().prj_nm());
            case "job_cd" -> Comparator.comparing(r -> r.source().job_cd());
            case "remaining_days" -> Comparator.comparingLong(Row::remaining_days);
            case "ext_psblty_cd" -> Comparator.comparing(r -> r.source().ext_psblty_cd(), Comparator.nullsLast(Comparator.naturalOrder()));
            case "end_dt" -> Comparator.comparing(r -> r.source().end_dt());
            default -> throw WithdrawalInput.error("sort");
        };
    }
}
