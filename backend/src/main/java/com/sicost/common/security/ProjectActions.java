package com.sicost.common.security;

import java.util.Map;
import java.util.Set;

public final class ProjectActions {
    private ProjectActions() {}

    private static final Map<String, Set<String>> OPERATIONS = Map.of(
        "read", Set.of("listProjects", "getProject", "listProjectHistory", "listProjectAssignments",
            "listProjectOptions", "listProjectPartChanges", "listProjectBillStages", "getProjectAllocations",
            "listProjectExpenses", "listProjectContractChanges", "getProjectStaffPlans",
            "getProjectProgress", "listProjectIssues", "getIssueOwnerOptions", "getParams"),
        "create", Set.of("createProject"),
        "update", Set.of("updateProject", "createProjectPartChange", "saveProjectBillStages",
            "saveProjectAllocations", "createProjectExpense", "updateProjectExpense", "updateProjectExpenseRemoval",
            "createProjectContractChange", "updateProjectContractChange", "updateProjectContractChangeRemoval",
            "saveProjectStaffPlans", "updateProjectPhase", "updateProjectPhaseCompletion",
            "updateProjectPhaseUncompletion", "saveProjectMemo", "updateProjectMemo", "createIssue", "updateIssue"),
        "delete", Set.of("updateProjectDisable", "updateProjectEnable"),
        "confirm", Set.of("updateProjectContractChangeConfirmation"),
        "upload", Set.of("createUploadValidation", "getUploadResult", "listUploadIssues", "listUploadRows",
            "createUploadRevalidation", "saveUploadApplication"),
        "download", Set.of("getProjectExport", "getUploadTemplate", "getUploadErrorFile"));

    private static final Map<String, String> BOARD_OPERATIONS = Map.of(
        "getProjectBoard", "UI-31.read",
        "listIssues", "UI-31.read",
        "getProjectBoardExport", "UI-31.download");

    public static String key(String operationId) {
        String boardKey = BOARD_OPERATIONS.get(operationId);
        if (boardKey != null) return boardKey;
        for (var action : OPERATIONS.entrySet()) {
            if (action.getValue().contains(operationId)) return "UI-01." + action.getKey();
        }
        throw new IllegalArgumentException("프로젝트 동작이 아닙니다.");
    }

    public static String key(String operationId, String screenId) {
        if ("createIssue".equals(operationId) && "UI-31".equals(screenId)) return "UI-31.create";
        String key = key(operationId);
        if (key.startsWith(screenId + ".")) return key;
        throw new IllegalArgumentException("프로젝트 화면 동작이 아닙니다.");
    }

    public static boolean can(PermissionService permissions, String operationId, boolean confirming) {
        boolean change = "createProjectContractChange".equals(operationId)
            || "updateProjectContractChange".equals(operationId);
        boolean allowed = permissions.can(key(operationId));
        if (change && confirming) allowed = permissions.can("UI-01.confirm") && allowed;
        return allowed;
    }
}
