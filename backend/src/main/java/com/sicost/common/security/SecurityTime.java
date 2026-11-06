package com.sicost.common.security;

import java.time.*;

public final class SecurityTime {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    public static final Duration MFA_LIFETIME = Duration.ofMinutes(5);
    public static final Duration RESET_LIFETIME = Duration.ofMinutes(15);
    public static final Duration ACTIVITY_MERGE = Duration.ofSeconds(30);
    private SecurityTime() {}
    public static LocalDateTime now(Clock clock) { return LocalDateTime.now(clock.withZone(SEOUL)); }
    public static boolean active(String status, LocalDate resignation, Clock clock) {
        return "ACTIVE".equals(status) && (resignation == null || resignation.isAfter(now(clock).toLocalDate()));
    }
    public static boolean sessionValid(LocalDateTime lastActivity, LocalDateTime expiresAt,
                                       LocalDateTime revokedAt, Clock clock) {
        LocalDateTime now = now(clock);
        return revokedAt == null && lastActivity != null && expiresAt != null &&
            !lastActivity.isAfter(now) && now.isBefore(expiresAt) && now.isBefore(lastActivity.plus(IDLE_TIMEOUT));
    }
}
