package com.sicost.partchanges;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;
import com.sicost.common.security.ProjectActions;
import com.sicost.common.validation.ProjectValues;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.partchanges.PartChangeModels.*;

@Service
@Transactional(readOnly = true)
public class PartChangeService {
    private final PartChangeMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;

    public PartChangeService(PartChangeMapper mapper, CurrentUser user, PermissionService permissions) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
    }

    private PartChangeProject require(long id) {
        var project = mapper.project(PartChangeInput.id(id));
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        return project;
    }

    public List<PartChange> list(long id) {
        permissions.can(ProjectActions.key("listProjectPartChanges"));
        require(id);
        return mapper.list(id);
    }

    @Transactional
    public List<PartChange> create(long id, PartChangeRequest request) {
        permissions.can(ProjectActions.key("createProjectPartChange"));
        var project = require(id);
        if ("N".equals(project.use_yn())) {
            throw ApiException.badRequest("사용 안 함 프로젝트는 다시 사용한 뒤 변경해 주세요.", "use_yn");
        }
        if ("IN".equals(project.cntrct_ty_cd())) {
            throw ApiException.badRequest("내부 프로젝트의 영업 파트는 변경할 수 없습니다.", "cntrct_ty_cd");
        }
        var input = PartChangeInput.validate(request);
        String name = mapper.activePartName(input.bsn_part_id());
        if (name == null || Objects.equals(project.bsn_part_id(), input.bsn_part_id())) {
            throw ApiException.badRequest("현재 파트가 아닌 사용 중인 영업 파트 또는 본사를 골라 주세요.", "bsn_part_id");
        }
        String closed = mapper.lastClosedMonth();
        try {
            ProjectValues.requirePartMonth(input.aply_bgn_ym(), closed);
        } catch (ApiException ex) {
            if (!"CLOSED_MONTH".equals(ex.code())) throw ex;
            throw ApiException.conflict("CLOSED_MONTH", "적용 시작 월은 " + closed + " 이후로 골라 주세요.", "aply_bgn_ym");
        }
        var previous = mapper.list(id).stream()
            .filter(row -> row.aply_bgn_ym().equals(input.aply_bgn_ym())).findFirst().orElse(null);
        String before = previous == null ? "대표 파트 " + Objects.toString(project.bsn_part_id(), "미지정")
            : summary(previous.partName(), previous.bsn_part_id(), previous.aply_bgn_ym(), previous.rsn_desc());
        var write = new PartChangeWrite(id, input, user.userId());
        mapper.upsert(write);
        mapper.updateRepresentative(write);
        mapper.audit(new PartChangeAudit(Long.toString(id), before,
            summary(name, input.bsn_part_id(), input.aply_bgn_ym(), input.rsn_desc()), input.rsn_desc(), user.userId()));
        return mapper.list(id);
    }

    private static String summary(String name, Long part, String month, String reason) {
        return "영업 파트 " + Objects.toString(name, "미지정") + " [" + Objects.toString(part, "")
            + "] · " + month + "부터 · 사유 " + Objects.toString(reason, "");
    }
}
