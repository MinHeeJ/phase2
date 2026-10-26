package com.sicost.common.validation;

import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ProjectPhases {
    private ProjectPhases() {}

    public record Definition(int sequence, String name, BigDecimal weight) {}
    public record Plan(int sequence, String name, BigDecimal weight, LocalDate start, LocalDate end) {}

    public static String codeGroup(String contractType) {
        ProjectValues.settlement(contractType, null);
        return contractType.equals("TK") || contractType.equals("IN") ? "PHASE_TK" : "PHASE_OP";
    }

    // Definitions come from the selected DB code group, never from a local phase catalogue.
    public static List<Plan> initialize(ProjectValues.PerformancePeriod period, List<Definition> definitions) {
        if (definitions == null || definitions.isEmpty()) throw invalid();
        var ordered = definitions.stream().sorted(Comparator.comparingInt(Definition::sequence)).toList();
        BigDecimal total = BigDecimal.ZERO;
        int previous = 0;
        for (var phase : ordered) {
            if (phase.sequence() <= previous || phase.name() == null || phase.name().isBlank()
                    || phase.weight() == null || phase.weight().signum() < 0) throw invalid();
            total = total.add(phase.weight());
            previous = phase.sequence();
        }
        if (total.compareTo(new BigDecimal("100")) != 0) throw invalid();
        var result = new ArrayList<Plan>();
        long remaining = period.days();
        LocalDate cursor = period.start();
        for (int index = 0; index < ordered.size(); index++) {
            var phase = ordered.get(index);
            long days = index == ordered.size() - 1 ? remaining : BigDecimal.valueOf(period.days())
                .multiply(phase.weight()).divide(new BigDecimal("100"), 0, RoundingMode.FLOOR).longValueExact();
            LocalDate end = days == 0 ? null : cursor.plusDays(days - 1);
            result.add(new Plan(phase.sequence(), phase.name(), phase.weight(), days == 0 ? null : cursor, end));
            if (days > 0) cursor = end.plusDays(1);
            remaining -= days;
        }
        return List.copyOf(result);
    }

    private static ApiException invalid() {
        return ApiException.badRequest("단계 코드의 순서와 비중 합계 100을 확인하세요.", "phases");
    }
}
