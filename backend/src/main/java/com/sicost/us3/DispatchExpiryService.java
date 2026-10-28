package com.sicost.us3;

import com.sicost.apiops.ProjectBoardMapper;
import com.sicost.changes.AssignmentChangeMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.validation.ProjectValues;
import java.time.*;
import java.util.*;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.us3.DispatchExpiryRules.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DispatchExpiryService {
    private final AssignmentChangeMapper assignments;
    private final ProjectBoardMapper standards;
    private final Clock clock;

    public DispatchExpiryService(AssignmentChangeMapper assignments, ProjectBoardMapper standards, Clock clock) {
        this.assignments = assignments;
        this.standards = standards;
        this.clock = clock;
    }

    public record Snapshot(LocalDate today, Map<Long, Display> assignments) {}

    public Snapshot snapshot(Collection<Long> assignmentIds) {
        LocalDate today = ProjectValues.today(clock);
        var parameters = parameters(standards.params(YearMonth.from(today).toString()));
        Map<Long, Display> result = new LinkedHashMap<>();
        for (long id : new LinkedHashSet<>(assignmentIds)) {
            if (id < 1) throw ApiException.badRequest("투입 번호를 확인하세요.", "id");
            var state = assignments.state(id);
            if (state == null) throw ApiException.notFound("투입이 없습니다.");
            LocalDate first = "DP".equals(state.cntrct_ty_cd()) ? assignments.firstStart(id) : null;
            result.put(id, display(state.cntrct_ty_cd(), first, state.end_dt(), today, parameters));
        }
        return new Snapshot(today, Collections.unmodifiableMap(result));
    }

    // Pass the complete filtered candidate set here, before computing total or slicing pages.
    public <T> List<T> filter(List<T> candidates, ToLongFunction<T> assignmentId,
            Snapshot snapshot, boolean enabled) {
        return candidates.stream().filter(row -> !enabled
            || Objects.requireNonNull(snapshot.assignments().get(assignmentId.applyAsLong(row))).within60()).toList();
    }
}
