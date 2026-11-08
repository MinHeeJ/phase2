package com.sicost.common.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SecurityDeploymentGate {
    public enum OperationalCheck { HTTPS_CSRF_COOKIE, MFA_DELIVERY, NOTIFICATION_DELIVERY, SESSION_TERMINATION,
        DATABASE_RESTORE, TWO_NODE_SESSIONS, SCHEDULER_DEDUPLICATION, THREE_YEAR_AUDIT_RETENTION, PERMISSION_REVIEW, MFA_CAPABLE_ADMINISTRATOR }
    public record Result(boolean ready,List<String> missing) { public Result { missing=List.copyOf(missing); } }
    private SecurityDeploymentGate() {}
    public static Result evaluate(Map<String,Boolean> acceptance,Set<OperationalCheck> receipts) {
        var missing=new ArrayList<String>();
        for(int id=1601;id<=1620;id++) { String key="AC-"+id;if(!Boolean.TRUE.equals(acceptance.get(key))) missing.add(key); }
        for(var check:OperationalCheck.values()) if(!receipts.contains(check)) missing.add(check.name());
        return new Result(missing.isEmpty(),missing);
    }
}
