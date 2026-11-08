package com.sicost.common.security;

import java.util.Map;

public final class ClosingActions {
    public static final String READ = "UI-14.read";
    public static final String CONFIRM = "UI-14.confirm";
    public static final String REOPEN = "UI-14.reopen";
    public static final String UPDATE = "UI-14.update";
    public static final String DOWNLOAD = "UI-14.download";

    private static final Map<String, String> OPERATIONS = Map.of(
        "getClosing", READ,
        "listClosingHistory", READ,
        "listClosingAdjustments", READ,
        "previewClosing", READ,
        "runClosingCheck", READ,
        "confirmClosing", CONFIRM,
        "reopenClosing", REOPEN,
        "createClosingAdjustment", UPDATE,
        "cancelClosingAdjustment", UPDATE);

    private ClosingActions() {}

    public static String key(String operationId) {
        String key = operationId == null ? null : OPERATIONS.get(operationId);
        if (key == null) throw new IllegalArgumentException("월 마감 동작이 아닙니다.");
        return key;
    }
}
