package com.sicost.common.persistence;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.AssignmentRules;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.persistence.AssignmentConcurrencyMapper.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AssignmentConcurrencyService {
    private final AssignmentConcurrencyMapper mapper;
    private final CurrentUser user;

    public AssignmentConcurrencyService(AssignmentConcurrencyMapper mapper, CurrentUser user) {
        this.mapper = mapper;
        this.user = user;
    }

    public void lockProject(long id) {
        if (id < 1) throw ApiException.badRequest("프로젝트를 확인하세요.", "prj_id");
        if (mapper.lockProject(id) == null) throw ApiException.notFound("프로젝트가 없습니다.");
    }

    public AssignmentLock lockAndCompare(long id, LocalDateTime expected) {
        if (id < 1) throw ApiException.badRequest("투입을 확인하세요.", "asgn_id");
        var row = mapper.lockAssignment(id);
        if (row == null) throw ApiException.notFound("투입이 없습니다.");
        AssignmentRules.compareLockedTimestamp(expected, row.updatedAt());
        // V1's trigger uses transaction time, not clock_timestamp(). A transaction that began
        // before the previous save must retry instead of moving the wire token backwards.
        if (!row.transactionAt().isAfter(row.updatedAt())) throw AssignmentRules.stale();
        return row;
    }

    public LocalDateTime advance(AssignmentLock locked) {
        var next = mapper.advance(new AssignmentTouch(locked.asgnId(), locked.updatedAt(), user.userId()));
        if (next == null || !next.isAfter(locked.updatedAt())) throw AssignmentRules.stale();
        return next;
    }
}
