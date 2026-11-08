package com.sicost.common.security;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class SecurityModels {
    private SecurityModels() {}
    public record Account(Long userId, String name, Long developerId, String status,
                          LocalDate resignationDate, boolean passwordChangeRequired) {}
    public record Session(String hash, Long userId, LocalDateTime createdAt, LocalDateTime lastActivityAt,
                          LocalDateTime expiresAt, LocalDateTime revokedAt, boolean passwordChangeOnly) {
        @Override public String toString() { return "Session[redacted]"; }
    }
    public record SessionWrite(String hash, Long userId, LocalDateTime now, LocalDateTime expiresAt,
                               boolean passwordChangeOnly) {}
    public record Permission(Long roleId, String roleName, DataScope scope, boolean admin,
                             boolean sensitive, boolean partStandardChange, String screenId,
                             String actions, boolean contactInquiry, boolean contactDownload) {}
    public record Resource(Long userId, Long partId, Long projectId, Long developerId) {}
    public record Assignments(List<Long> partIds, List<Long> projectIds) {
        public Assignments { partIds=List.copyOf(partIds); projectIds=List.copyOf(projectIds); }
    }
}
