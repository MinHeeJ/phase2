package com.sicost.common.security;

import java.util.*;

/** Release automation supplies real receipts; absent evidence can never mean completion. */
public final class SecurityDeploymentGate {
    public enum Check { HTTPS_CSRF_COOKIE, MFA_MAIL, NOTIFICATION_SEND_RECEIVE, SESSION_TERMINATION,
        DATABASE_BACKUP_RESTORE, TWO_NODE_SESSION_REVOCATION, SCHEDULER_DEDUPLICATION,
        AUDIT_THREE_YEAR_RETENTION, PERMISSION_REVIEW }
    public record Receipt(boolean passed, String evidence) {
        public boolean verified() { return passed && evidence != null && !evidence.isBlank(); }
    }
    public List<String> incomplete(Map<String,Receipt> acceptance, Map<Check,Receipt> deployment) {
        List<String> missing = new ArrayList<>();
        for (int id=1601; id<=1620; id++) {
            String key="AC-"+id;
            if (acceptance == null || acceptance.get(key) == null || !acceptance.get(key).verified()) missing.add(key);
        }
        for (Check check : Check.values()) {
            if (deployment == null || deployment.get(check) == null || !deployment.get(check).verified()) missing.add(check.name());
        }
        return List.copyOf(missing);
    }
    public void requireReady(Map<String,Receipt> acceptance, Map<Check,Receipt> deployment) {
        if (!incomplete(acceptance,deployment).isEmpty()) throw new IllegalStateException("보안 배포 검증 증거가 완전하지 않습니다.");
    }
}
