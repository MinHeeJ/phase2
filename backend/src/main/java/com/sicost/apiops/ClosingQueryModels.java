package com.sicost.apiops;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class ClosingQueryModels {
    private ClosingQueryModels() {}
    public record State(String status, LocalDateTime processedAt, Long actorId, String actorName) {}
    public record StoredCheck(String code, String status, int count, String description, LocalDateTime checkedAt) {}
    public record Check(String code, String status, int count, String description) {}
    public record DashboardClosing(String status,String lastClosedMonth,List<StoredCheck> checks,boolean missingFrozenChecks) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Closing(String month, String status, List<Check> checks, OffsetDateTime checkedAt,
            boolean canReopen, int warningCount, int failureCount, OffsetDateTime processedAt,
            Long actorId, String actorName) {}
}
