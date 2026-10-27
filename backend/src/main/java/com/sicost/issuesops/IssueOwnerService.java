package com.sicost.issuesops;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.PermissionService;
import com.sicost.common.security.ProjectActions;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.issuesops.IssueOwnerModels.*;

@Service
@Transactional(readOnly = true)
public class IssueOwnerService {
    private final IssueOwnerMapper mapper;
    private final PermissionService permissions;
    private final Clock clock;

    public IssueOwnerService(IssueOwnerMapper mapper, PermissionService permissions, Clock clock) {
        this.mapper = mapper;
        this.permissions = permissions;
        this.clock = clock;
    }

    public List<IssueOwnerOption> options(long projectId) {
        ProjectActions.can(permissions, "getIssueOwnerOptions", false);
        if (projectId < 1) throw ApiException.badRequest("프로젝트를 먼저 고르세요.", "projectId");
        if (!mapper.projectExists(projectId)) throw ApiException.notFound("프로젝트가 없습니다.");
        return mapper.options(new IssueOwnerQuery(projectId, LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))));
    }
}
