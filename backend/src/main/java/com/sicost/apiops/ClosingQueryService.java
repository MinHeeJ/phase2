package com.sicost.apiops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ClosingValues;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.apiops.ClosingQueryModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ClosingQueryService {
    private final ClosingQueryMapper mapper;
    private final ObjectMapper json;
    public ClosingQueryService(ClosingQueryMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public Closing getClosing(String requested) {
        String month = ClosingValues.month(requested, "month").toString();
        State state = mapper.state(month);
        boolean closed = state != null && "CLOSED".equals(state.status());
        if (closed) requireSnapshots(month);
        List<StoredCheck> stored = mapper.checks(month);
        List<Check> checks = stored.stream()
            .map(row -> new Check(row.code(), row.status(), row.count(), row.description())).toList();
        LocalDateTime checkedAt = stored.stream().map(StoredCheck::checkedAt).filter(Objects::nonNull)
            .max(Comparator.naturalOrder()).orElse(null);
        boolean canReopen = closed && month.equals(mapper.latestClosedMonth()) && mapper.activeReferences(month) == 0;
        return new Closing(month, closed ? "CLOSED" : "OPEN", checks, timestamp(checkedAt), canReopen,
            (int) checks.stream().filter(row -> "WARN".equals(row.status())).count(),
            (int) checks.stream().filter(row -> "FAIL".equals(row.status())).count(),
            closed ? timestamp(state.processedAt()) : null, closed ? state.actorId() : null,
            closed ? state.actorName() : null);
    }

    private OffsetDateTime timestamp(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }

    private void requireSnapshots(String month) {
        String manifest = mapper.closeManifest(month);
        String evidence = mapper.snapshotEvidence(month);
        try {
            JsonNode recorded = manifest == null ? null : json.readTree(manifest).get("snapshots");
            JsonNode actual = evidence == null ? null : json.readTree(evidence);
            if (recorded == null || actual == null || !actual.isObject()
                    || !actual.hasNonNull("allocation") || !actual.hasNonNull("details")
                    || !actual.hasNonNull("profit") || !actual.hasNonNull("tax")
                    || !actual.hasNonNull("cash") || !recorded.equals(actual)) throw missingSnapshot();
        } catch (JsonProcessingException ex) {
            throw missingSnapshot();
        }
    }

    private ApiException missingSnapshot() {
        return ApiException.conflict("MISSING_CLOSED_SNAPSHOT", "마감 스냅샷이 없거나 저장 내역과 일치하지 않습니다.", "month");
    }
}
