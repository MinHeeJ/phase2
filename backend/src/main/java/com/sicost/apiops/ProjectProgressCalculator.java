package com.sicost.apiops;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.sicost.apiops.ProjectBoardModels.*;

public final class ProjectProgressCalculator {
    private static final Set<String> BOARD_PARAMETER_KEYS = Set.of(
        "SIG_DELAY_WARN", "SIG_DELAY_RISK", "SIG_COST_WARN", "SIG_COST_RISK",
        "SIG_STALE_DAYS", "BUDGET_RATE");
    private ProjectProgressCalculator() {}
    public static BigDecimal rounded(BigDecimal value) { return value.setScale(1, RoundingMode.HALF_UP); }
    public static Phase phase(Phase p, LocalDate today) {
        String status = p.cmpl_dt() != null ? "완료" : p.plan_bgn_dt() == null || p.plan_end_dt() == null ? "예정"
            : p.plan_end_dt().isBefore(today) ? "지연" : !p.plan_bgn_dt().isAfter(today) ? "진행 중" : "예정";
        return new Phase(p.phs_id(), p.prj_id(), p.phs_seq(), p.phs_nm(), p.wght_rt(),
            p.plan_bgn_dt(), p.plan_end_dt(), p.cmpl_dt(), status);
    }
    public static BoardRow calculate(BoardSource p, List<Phase> phases, ProjectCost cost,
            List<OperatingParam> parameters, LocalDate today) {
        var values = new HashMap<String, BigDecimal>();
        parameters.stream().filter(v -> BOARD_PARAMETER_KEYS.contains(v.oprt_std_id()))
            .forEach(v -> values.put(v.oprt_std_id(), v.numeric()));
        BigDecimal planned = BigDecimal.ZERO;
        for (var phase : phases) {
            var start = phase.plan_bgn_dt(); var end = phase.plan_end_dt();
            if (start == null || end == null || start.isAfter(today)) continue;
            if (end.isBefore(today)) planned = planned.add(phase.wght_rt());
            else planned = planned.add(phase.wght_rt().multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, today)))
                .divide(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end) + 1), 12, RoundingMode.HALF_UP));
        }
        planned = rounded(planned.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)));
        BigDecimal actual = Set.of("MO", "DP").contains(p.cntrct_ty_cd()) ? planned
            : rounded(p.actl_prgs_rt() == null ? BigDecimal.ZERO : p.actl_prgs_rt());
        BigDecimal difference = rounded(actual.subtract(planned));
        Long budget = null, cumulative = null;
        BigDecimal use = null, costDifference = null;
        if ("TK".equals(p.cntrct_ty_cd()) && p.trgt_cst_amt() != null) {
            long computed = p.trgt_cst_amt();
            if (computed > 0) {
                budget = computed; cumulative = cost.cumulative_cost_amt();
                use = BigDecimal.valueOf(cumulative).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(budget), 1, RoundingMode.HALF_UP);
                costDifference = rounded(use.subtract(actual));
            }
        }
        LocalDate last = p.last_memo_dt() == null ? p.prfm_bgn_dt() : p.last_memo_dt();
        Integer elapsed = last == null ? null : Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(last, today)));
        boolean scheduleRisk = !phases.isEmpty() && difference.compareTo(values.get("SIG_DELAY_RISK").negate()) <= 0;
        boolean scheduleWarn = !phases.isEmpty() && difference.compareTo(values.get("SIG_DELAY_WARN").negate()) <= 0;
        boolean costRisk = !phases.isEmpty() && costDifference != null && costDifference.compareTo(values.get("SIG_COST_RISK")) >= 0;
        boolean costWarn = !phases.isEmpty() && costDifference != null && costDifference.compareTo(values.get("SIG_COST_WARN")) >= 0;
        boolean stale = elapsed != null && BigDecimal.valueOf(elapsed).compareTo(values.get("SIG_STALE_DAYS")) > 0;
        String light = scheduleRisk || costRisk || p.high_overdue_count() > 0 ? "위험"
            : scheduleWarn || costWarn || p.overdue_issue_count() > 0 || stale ? "주의" : "정상";
        var reasons = new ArrayList<String>();
        if ("TK".equals(p.cntrct_ty_cd()) && budget == null) reasons.add("목표 원가 없음: 원가 사용률 산정 불가");
        if (scheduleRisk || scheduleWarn) reasons.add("일정이 계획보다 " + difference.abs().toPlainString() + "%p 늦음");
        if (costRisk || costWarn) reasons.add("원가 사용률이 진척률보다 " + costDifference.toPlainString() + "%p 높음" + (costRisk ? " — 원가 초과 위험" : ""));
        if (p.high_overdue_count() > 0) reasons.add("기한 지난 중요(높음) 이슈 " + p.high_overdue_count() + "건");
        else if (p.overdue_issue_count() > 0) reasons.add("기한 지난 이슈 " + p.overdue_issue_count() + "건");
        if (stale) reasons.add(elapsed + "일째 상태 업데이트 없음");
        if (reasons.isEmpty()) reasons.add("계획대로 진행 중입니다.");
        var ordered = phases.stream().sorted(Comparator.comparingInt(Phase::phs_seq)).toList();
        Phase current = ordered.stream().filter(v -> v.cmpl_dt() == null).findFirst()
            .orElse(ordered.isEmpty() ? null : ordered.get(ordered.size() - 1));
        if (current != null) current = phase(current, today);
        return new BoardRow(p.prj_id(), p.pmName(), current, planned, actual, difference, use, costDifference,
            cumulative, budget, light, List.copyOf(reasons), elapsed, p.last_memo_dt(), p.prj_no(), p.prj_nm(),
            p.custName(), p.cntrct_ty_cd(), p.open_issue_count(), p.overdue_issue_count());
    }
}
