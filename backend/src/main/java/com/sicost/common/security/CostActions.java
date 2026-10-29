package com.sicost.common.security;

import java.util.Map;
import java.util.Set;

public final class CostActions {
    private CostActions() {}
    private static final Map<String, Set<String>> OPERATIONS = Map.of(
        "UI-03.read", Set.of("listCostPeople", "getCostPersonBreakdown"),
        "UI-03.download", Set.of("UI-03.download"),
        "UI-04.read", Set.of("listCostProjects", "getCostProject", "getCostProjectCauses", "simulateCostProject"),
        "UI-04.download", Set.of("UI-04.download"),
        "UI-19.read", Set.of("listPartSettlements", "getPartSettlement"),
        "UI-19.update", Set.of("updatePartSettlementCard"),
        "UI-19.confirm", Set.of("confirmPartSettlement"),
        "UI-19.cancel", Set.of("cancelPartSettlement"),
        "UI-19.download", Set.of("UI-19.download"),
        "UI-32.read", Set.of("listCostRenegotiations"));
    public static String key(String operationId) {
        if ("UI-32.download".equals(operationId)) return operationId;
        for (var entry : OPERATIONS.entrySet()) {
            if (entry.getValue().contains(operationId)) return entry.getKey();
        }
        throw new IllegalArgumentException("원가 분석 동작이 아닙니다.");
    }
}
