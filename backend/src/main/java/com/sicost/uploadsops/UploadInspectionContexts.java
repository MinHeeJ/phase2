package com.sicost.uploadsops;

import com.sicost.common.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class UploadInspectionContexts {
    public record Inspection(long uploadId, String formType, String month, int rowCount,
                             int errorCount, int warningCount, boolean canApply,
                             boolean normalizedRowsAvailable) {
        public Inspection {
            if (uploadId < 1 || !Set.of("B.8", "B.1", "B.3").contains(formType)
                    || rowCount < 0 || errorCount < 0 || warningCount < 0
                    || errorCount > rowCount || warningCount > rowCount) {
                throw new IllegalArgumentException("올바른 검사 요약이 필요합니다.");
            }
            if ((Set.of("B.1", "B.3").contains(formType) && (month == null || !month.matches("[0-9]{4}-(0[1-9]|1[0-2])")))
                    || ("B.8".equals(formType) && month != null)) {
                throw new IllegalArgumentException("검사 대상 연월을 확인하세요.");
            }
            canApply = canApply && errorCount == 0 && rowCount > 0;
        }
        Inspection readOnly() {
            return new Inspection(uploadId, formType, month, rowCount, errorCount, warningCount,
                    false, normalizedRowsAvailable);
        }
    }
    private record Entry(Inspection inspection, Instant expiresAt) {}
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Clock clock;

    public UploadInspectionContexts(Clock clock) { this.clock = clock; }

    // The upload orchestration publishes its reserved numeric ID with its original lease.
    public synchronized void publish(Inspection inspection, Instant validatedAt) {
        Instant expiresAt = validatedAt.plus(Duration.ofMinutes(30));
        entries.entrySet().removeIf(entry -> !clock.instant().isBefore(entry.getValue().expiresAt()));
        if (!clock.instant().isBefore(expiresAt)) throw expired();
        if (validatedAt.isAfter(clock.instant())) throw new IllegalArgumentException("검사 시각을 확인하세요.");
        if (entries.containsKey(inspection.uploadId())) {
            throw new IllegalArgumentException("검사 ID는 다시 사용할 수 없습니다.");
        }
        entries.put(inspection.uploadId(), new Entry(inspection, expiresAt));
    }

    public synchronized Inspection require(long uploadId) {
        Entry entry = entries.get(uploadId);
        if (entry == null || !clock.instant().isBefore(entry.expiresAt())) throw expired();
        return entry.inspection();
    }

    // Revalidation and committed application both retire applicability, not the review summary.
    public synchronized void invalidate(long uploadId) {
        Inspection inspection = require(uploadId);
        Entry entry = entries.get(uploadId);
        entries.put(uploadId, new Entry(inspection.readOnly(), entry.expiresAt()));
    }

    private static ApiException expired() {
        return new ApiException(410, "VALIDATION_EXPIRED", "검사 결과가 만료되었습니다. 파일을 다시 올려 주세요.", "uploadId");
    }
}
