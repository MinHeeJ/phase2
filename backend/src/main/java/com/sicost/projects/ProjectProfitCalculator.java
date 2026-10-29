package com.sicost.projects;

import com.sicost.common.api.ApiException;
import java.math.*;
import java.util.*;
import static com.sicost.projects.ProjectProfitModels.*;

public final class ProjectProfitCalculator {
    private ProjectProfitCalculator() {}
    public static BigDecimal ratio(BigDecimal amount, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null
            : amount.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }
    public static BigDecimal margin(Long revenue, long cost) {
        return revenue == null ? null : ratio(BigDecimal.valueOf(revenue).subtract(BigDecimal.valueOf(cost)), BigDecimal.valueOf(revenue));
    }
    public static boolean lowMargin(BigDecimal margin, BigDecimal target) {
        return margin != null && target != null && margin.compareTo(target.subtract(BigDecimal.valueOf(5))) < 0;
    }
    public static boolean lowMarginFor(Long revenue, long cost, BigDecimal target) {
        if (revenue == null || revenue == 0 || target == null) return false;
        BigDecimal exact = BigDecimal.valueOf(revenue).subtract(BigDecimal.valueOf(cost))
            .multiply(BigDecimal.valueOf(100));
        BigDecimal boundary = BigDecimal.valueOf(revenue).multiply(target.subtract(BigDecimal.valueOf(5)));
        return revenue > 0 ? exact.compareTo(boundary) < 0 : exact.compareTo(boundary) > 0;
    }
    public static String budgetAlert(long cost, Long budget) {
        if (budget == null || budget <= 0) return "UNAVAILABLE";
        var percent = BigDecimal.valueOf(cost).multiply(BigDecimal.valueOf(100));
        if (percent.compareTo(BigDecimal.valueOf(budget).multiply(BigDecimal.valueOf(100))) >= 0) return "RISK";
        if (percent.compareTo(BigDecimal.valueOf(budget).multiply(BigDecimal.valueOf(80))) >= 0) return "WARN";
        return "NORMAL";
    }
    public static Causes causes(Source source, int year, List<PersonMonth> people,
            BigDecimal plannedMm, BigDecimal threshold, List<String> warnings) {
        BigDecimal actual = BigDecimal.ZERO, cost = BigDecimal.ZERO, free = BigDecimal.ZERO;
        var grouped = new LinkedHashMap<String, BigDecimal[]>();
        for (var p : people) {
            actual = actual.add(p.mm_qty());
            var amount = BigDecimal.valueOf(p.lbr_cst_amt()).add(BigDecimal.valueOf(p.drct_expns_amt()));
            cost = cost.add(amount);
            if (p.free_sprt()) free = free.add(amount);
            var values = grouped.computeIfAbsent(p.month()+":"+p.dev_id(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            values[0] = values[0].add(amount);
            if (p.reference_revenue() != null) values[1] = values[1].add(BigDecimal.valueOf(p.reference_revenue()));
        }
        BigDecimal excess = actual.subtract(plannedMm).max(BigDecimal.ZERO);
        BigDecimal staffing = actual.signum() == 0 ? BigDecimal.ZERO
            : excess.multiply(cost).divide(actual, 0, RoundingMode.FLOOR);
        BigDecimal pricing = grouped.values().stream().map(v -> v[0].subtract(v[1].multiply(threshold).movePointLeft(2)).max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(0, RoundingMode.FLOOR);
        String owner = Objects.toString(source.pm_name(), "PM 미지정");
        var items = new ArrayList<Cause>();
        items.add(new Cause("STAFFING", "투입 초과", staffing,
            "max(실제 MM−현재까지 계획 MM,0)×MM당 배분 원가", owner,
            "인원 계획과 실제 투입을 확인하세요.", "/UI-11?projectId="+source.prj_id()+"&year="+year, null));
        items.add(new Cause("PRICING", "단가 대비 원가", pricing,
            "사람·월별 max(배분 원가−개인 참고매출×"+threshold.toPlainString()+"%,0)의 합", owner,
            "단가와 배분 원가를 점검하세요.", null, "단가 재협상 화면은 기존 /UI-32 개발자 등록과 주소가 충돌하여 이동할 수 없습니다."));
        items.add(new Cause("FREE", "무상지원", free.setScale(0, RoundingMode.FLOOR),
            "무상지원 MM의 배분 인건비·경비 (총원가에 이미 포함)", owner,
            "무상지원 범위와 계약을 확인하세요.", "/UI-04?open="+source.prj_id(), null));
        items.sort(Comparator.comparing(Cause::amount).reversed());
        String primary = items.stream().filter(c -> c.amount().signum() > 0).map(Cause::code).findFirst().orElse(null);
        return new Causes(source.prj_id(), year, List.copyOf(items), primary, null, null, warnings);
    }
    public static Simulation simulate(String type, Scenario scenario) {
        if (scenario == null || scenario.monthly_cost() == null || scenario.monthly_cost().signum() < 0
                || scenario.months() < 1 || scenario.months() > 120
                || (Set.of("MO", "DP").contains(type) && (scenario.monthly_price() == null || scenario.monthly_price().signum() < 0))) {
            throw new ApiException(400, "VALIDATION_ERROR", "가상 계산 값을 확인하세요.", null);
        }
        BigDecimal multiplier = BigDecimal.valueOf(scenario.people_change()).multiply(BigDecimal.valueOf(scenario.months()));
        BigDecimal cost = multiplier.multiply(scenario.monthly_cost()).setScale(0, RoundingMode.FLOOR);
        BigDecimal revenue = Set.of("MO", "DP").contains(type)
            ? multiplier.multiply(scenario.monthly_price()).setScale(0, RoundingMode.FLOOR) : BigDecimal.ZERO;
        return new Simulation(true, cost, revenue, revenue.subtract(cost));
    }
}
