package com.sicost.common.security;

import java.util.Map;
import java.util.Set;

public final class AssignmentActions {
    private AssignmentActions() {}
    private static final Map<String, Set<String>> OPERATIONS = Map.of(
        "read", Set.of("listAssignments", "getAssignment", "getAssignmentMonths",
            "listAssignmentChanges", "getProjectAssignmentMonths"),
        "create", Set.of("createAssignment"),
        "update", Set.of("checkAssignments", "saveAssignmentMonths", "createAssignmentChange", "createAssignmentReplacement",
            "createAssignmentFix", "saveAssignmentExtChance"),
        "delete", Set.of("saveAssignmentCancellation"),
        "upload", Set.of("createUploadValidation", "getUploadResult", "listUploadIssues",
            "listUploadRows", "createUploadRevalidation", "saveUploadApplication"),
        "download", Set.of("getAssignmentsExport", "getUploadTemplate", "getUploadErrorFile"));

    public static String key(String operationId) {
        for (var entry : OPERATIONS.entrySet()) {
            if (entry.getValue().contains(operationId)) return "UI-12." + entry.getKey();
        }
        throw new IllegalArgumentException("투입 동작이 아닙니다.");
    }

    public static boolean can(PermissionService permissions, String operationId) {
        return permissions.can(key(operationId));
    }
}
