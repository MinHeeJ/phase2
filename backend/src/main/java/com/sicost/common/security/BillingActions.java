package com.sicost.common.security;

import java.util.Map;
import java.util.Set;

public final class BillingActions {
    public static final String READ = "UI-13.read";
    public static final String CREATE = "UI-13.create";
    public static final String UPDATE = "UI-13.update";
    public static final String CONFIRM = "UI-13.confirm";
    public static final String CANCEL = "UI-13.cancel";
    public static final String UPLOAD = "UI-13.upload";
    public static final String DOWNLOAD = "UI-13.download";
    public static final String RECEIPT = "UI-13.receipt";
    public static final String REMIND = "UI-13.remind";

    private BillingActions() {}

    private static final Map<String, Set<String>> OPERATIONS = Map.of(
        READ, Set.of("listBillings", "getBilling", "previewBilling", "listBillingReceipts",
            "getDispatchAttendance", "listBillingGaps", "listBillingOverdue", "getBillingSummary"),
        CREATE, Set.of("createBilling", "createBillingAmendment"),
        UPDATE, Set.of("updateBilling", "saveDispatchAttendance"),
        CONFIRM, Set.of("confirmBilling"),
        CANCEL, Set.of("cancelBilling"),
        RECEIPT, Set.of("createBillingReceipt", "updateBillingReceipt", "deleteBillingReceipt"),
        UPLOAD, Set.of("validateDispatchAttendanceUpload", "applyDispatchAttendanceUpload",
            "getUploadResult", "listUploadIssues", "listUploadRows", "createUploadRevalidation", "saveUploadApplication"),
        DOWNLOAD, Set.of("exportBillings", "exportBillingGaps", "exportBillingOverdue",
            "getUploadTemplate", "getUploadErrorFile"));

    public static String key(String operationId) {
        if (operationId != null) {
            for (var entry : OPERATIONS.entrySet()) {
                if (entry.getValue().contains(operationId)) return entry.getKey();
            }
        }
        throw new IllegalArgumentException("청구 동작이 아닙니다.");
    }

    // Shared upload consumers select this registry only after resolving the actual B.3 form.
    public static boolean can(PermissionService permissions, String operationId) {
        return permissions.can(key(operationId));
    }
}
