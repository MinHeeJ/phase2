package com.sicost.closingops;

import com.sicost.common.security.Sensitive;
import com.sicost.costallocation.CostAllocationModels.AllocationRow;
import com.sicost.profit.MonthlyProfitModels.Detail;
import java.time.*;
import java.util.*;

public final class ClosingModels {
    private ClosingModels() {}
    public record Check(String code, String status, int count, String description) {}
    public record CheckResult(String month, String status, List<Check> checks, OffsetDateTime checkedAt) {}
    public record CheckRun(String month, List<Check> checks, String results, Long actor, LocalDateTime at) {}
    public record Transition(String month, String action, String reason, Long actor, LocalDateTime at) {}
    public record Result(String month, String status, OffsetDateTime processedAt, String actor) {}
    public record HistoryRow(long id, String month, String action, LocalDateTime processedAt, String actor, String reason) {}
    public record History(String month, String action, OffsetDateTime processedAt, String actor, String reason) {}
    public record HistoryQuery(int year, int page, int size, long offset) {}
    public record HistoryPage(List<History> items, int page, int size, long total) {}
    public record Preview(String month, @Sensitive long profit, @Sensitive long cost, @Sensitive long tax,
            @Sensitive long cash, List<String> warnings) {}
    public record StoredPreview(String month, long profit, long cost, long tax, long cash) {}
    public record Components(String month, List<AllocationRow> allocations, Map<String,Object> profit,
            List<Detail> details, long tax, long opening, long cash, List<String> warnings) {}
    public record SnapshotWrite(Components components, Long actor, LocalDateTime at) {}
    public record DetailWrite(String month, Detail detail) {}
    public record Audit(Transition transition, String description) {}
    public static OffsetDateTime timestamp(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }
}
