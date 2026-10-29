package com.sicost.common.security;

import java.util.Map;
import java.util.Set;

public final class BillingActions {
    private BillingActions() {}
    private static final Map<String, Set<String>> OPERATIONS = Map.of(
        "read", Set.of("listBillings", "previewBilling", "getBilling", "listBillingReceipts",
            "getDispatchAttendance", "listBillingGaps", "listBillingOverdue", "getBillingSummary"),
        "create", Set.of("createBilling", "createBillingAmendment"),
        "update", Set.of("updateBilling", "saveDispatchAttendance"),
        "confirm", Set.of("confirmBilling"),
        "cancel", Set.of("cancelBilling"),
        "receipt", Set.of("createBillingReceipt", "updateBillingReceipt", "deleteBillingReceipt"),
        "upload", Set.of("validateDispatchAttendanceUpload", "applyDispatchAttendanceUpload"));

    public static String key(String operationId) {
        for (var entry : OPERATIONS.entrySet()) {
            if (entry.getValue().contains(operationId)) return "UI-13." + entry.getKey();
        }
        throw new IllegalArgumentException("청구 동작이 아닙니다.");
    }

    public static String reminderKey() { return "UI-13.remind"; }
    public static String downloadKey() { return "UI-13.download"; }

    public static boolean can(PermissionService permissions, String operationId) {
        return permissions.can(key(operationId));
    }
}
