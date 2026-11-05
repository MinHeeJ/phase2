package com.sicost.common.security;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public final class AccessPolicyModels {
    private AccessPolicyModels() {}
    public record SessionPrincipal(Long userId, String sessionIdHash) implements java.security.Principal {
        public SessionPrincipal {
            if (userId == null || userId <= 0 || sessionIdHash == null || !sessionIdHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid authenticated identity");
            }
        }
        @Override public String getName() { return userId.toString(); }
        @Override public String toString() { return "SessionPrincipal[redacted]"; }
    }
    public record Account(Long userId, String name, Long developerId, String status,
                          LocalDate resignDate, boolean passwordChangeRequired, LocalDateTime expiresAt) {}
    public record Role(Long roleId, String name, DataScope scope, boolean sensitive,
                       boolean partStandardChange, boolean admin, boolean builtIn) {}
    public record Grant(Long roleId, DataScope scope, boolean sensitive, boolean partStandardChange,
                        boolean admin, boolean builtIn, String roleName) {}
    public record SessionLookup(Long userId, String sessionIdHash, LocalDateTime now) {
        @Override public String toString() { return "SessionLookup[redacted]"; }
    }
    public record PermissionLookup(Long userId, String screenId, String action) {}
    public record InitialAdministrator(String loginId, String passwordHash, String email) {
        @Override public String toString() { return "InitialAdministrator[redacted]"; }
    }
    public record Target(Long userId, Long developerId, Long partId, Long projectId) {}
    public record AuthorizedScope(Long userId, Long developerId, List<Long> partIds,
                                  List<Long> projectIds, List<Grant> grants) {
        public AuthorizedScope {
            partIds = List.copyOf(partIds); projectIds = List.copyOf(projectIds); grants = List.copyOf(grants);
        }
        public boolean permits(Target target) { return grants.stream().anyMatch(g -> matches(g, target)); }
        public boolean permitsSensitive(Target target) {
            return grants.stream().anyMatch(g -> g.sensitive() && matches(g, target));
        }
        private boolean matches(Grant grant, Target target) {
            if (target == null) return false;
            return switch (grant.scope()) {
                case ALL -> true;
                case PART -> target.partId() != null && partIds.contains(target.partId());
                case PROJECT -> target.projectId() != null && projectIds.contains(target.projectId());
                case SELF -> (target.userId() != null && Objects.equals(userId, target.userId()))
                    || (developerId != null && Objects.equals(developerId, target.developerId()));
            };
        }
    }
}
