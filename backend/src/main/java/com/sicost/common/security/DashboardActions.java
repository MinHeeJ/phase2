package com.sicost.common.security;

public final class DashboardActions {
    public static final String READ = "UI-16.read";
    public static final String DOWNLOAD = "UI-16.download";

    private DashboardActions() {}

    public static String key(String operationId) {
        if ("getDashboard".equals(operationId)) return READ;
        if ("exportDashboard".equals(operationId)) return DOWNLOAD;
        throw new IllegalArgumentException("대시보드 조회 동작이 아닙니다.");
    }

    public static boolean can(PermissionService permissions,String operationId) {
        return permissions.can(key(operationId));
    }
}
