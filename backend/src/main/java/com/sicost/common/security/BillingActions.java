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
        "upload", Set.of("validateDispatchAttendanceUpload", "applyDispatchAttendanceUpload"),
        "download", Set.of("getBillingsExport", "getBillingGapsExport", "getBillingOverdueExport"));

    public static String key(String operationId) {
        for (var entry : OPERATIONS.entrySet()) {
            if (entry.getValue().contains(operationId)) return slot(entry.getKey());
        }
        throw new IllegalArgumentException("청구 동작이 아닙니다.");
    }

    public static String slot(String action) {
        if (action == null || (!OPERATIONS.containsKey(action) && !"remind".equals(action))) {
            throw new IllegalArgumentException("청구 권한 자리가 아닙니다.");
        }
        return "UI-13." + action;
    }

    public static boolean can(PermissionService permissions, String operationId) {
        return permissions.can(key(operationId));
    }
}
