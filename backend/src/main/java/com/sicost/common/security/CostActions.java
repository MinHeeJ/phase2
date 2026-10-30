package com.sicost.common.security;

import java.util.Map;
import java.util.Set;

public final class CostActions {
    public static final String PEOPLE_READ = "UI-03.read";
    public static final String PEOPLE_DOWNLOAD = "UI-03.download";
    public static final String PROJECT_READ = "UI-04.read";
    public static final String PROJECT_DOWNLOAD = "UI-04.download";
    public static final String SETTLEMENT_READ = "UI-19.read";
    public static final String SETTLEMENT_UPDATE = "UI-19.update";
    public static final String SETTLEMENT_CONFIRM = "UI-19.confirm";
    public static final String SETTLEMENT_CANCEL = "UI-19.cancel";
    public static final String SETTLEMENT_DOWNLOAD = "UI-19.download";
    public static final String RENEGOTIATION_READ = "UI-32.read";
    public static final String RENEGOTIATION_DOWNLOAD = "UI-32.download";

    private static final Map<String, Set<String>> OPERATIONS = Map.of(
        PEOPLE_READ, Set.of("listCostPeople", "getCostPersonBreakdown"),
        PROJECT_READ, Set.of("listCostProjects", "getCostProject", "simulateCostProject",
            "getCostProjectCauses", "getCostAllocation"),
        SETTLEMENT_READ, Set.of("listPartSettlements", "getPartSettlement"),
        SETTLEMENT_UPDATE, Set.of("updatePartSettlementCard"),
        SETTLEMENT_CONFIRM, Set.of("confirmPartSettlement"),
        SETTLEMENT_CANCEL, Set.of("cancelPartSettlement"),
        RENEGOTIATION_READ, Set.of("listCostRenegotiations"));
    private static final Map<String, String> DOWNLOADS = Map.of(
        "listCostPeople", PEOPLE_DOWNLOAD, "listCostProjects", PROJECT_DOWNLOAD,
        "listPartSettlements", SETTLEMENT_DOWNLOAD, "listCostRenegotiations", RENEGOTIATION_DOWNLOAD);

    private CostActions() {}

    public static String key(String operationId) {
        if (operationId != null) {
            for (var entry : OPERATIONS.entrySet()) {
                if (entry.getValue().contains(operationId)) return entry.getKey();
            }
        }
        throw new IllegalArgumentException("원가 분석 동작이 아닙니다.");
    }

    public static String downloadKey(String operationId) {
        String key = operationId == null ? null : DOWNLOADS.get(operationId);
        if (key == null) throw new IllegalArgumentException("원가 분석 다운로드 동작이 아닙니다.");
        return key;
    }
}
