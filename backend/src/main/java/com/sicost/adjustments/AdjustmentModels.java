package com.sicost.adjustments;

import com.sicost.common.security.Sensitive;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.util.List;

public final class AdjustmentModels {
    private AdjustmentModels() {}
    public record Input(String originalMonth, String applyMonth, String type, long amount,
            String reason, Long projectId, Long developerId, Long originalAdjustmentId) {}
    public record Row(long id, String originalMonth, String applyMonth, String type,
            @Sensitive long amount, String reason, String status, OffsetDateTime createdAt,
            OffsetDateTime cancelledAt, Long originalAdjustmentId) {}
    public record Query(int year, String month, int page, int size, long offset) {}
    public record Page(List<Row> items, int page, int size, long total) {}
    public record Write(Input input, Long actor, LocalDateTime now) {}
    public record Cancel(long id, String reason, Long actor, LocalDateTime now) {}
    public record Audit(long id, String before, String after, String reason, Long actor, String action) {}
}
