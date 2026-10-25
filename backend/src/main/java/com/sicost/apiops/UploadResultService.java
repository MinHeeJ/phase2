package com.sicost.apiops;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.PermissionService;
import com.sicost.uploadsops.UploadInspectionContexts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.apiops.UploadResultModels.*;

@Service
@Transactional(readOnly = true)
public class UploadResultService {
    private final UploadInspectionContexts contexts;
    private final UploadResultMapper mapper;
    private final PermissionService permissions;

    public UploadResultService(UploadInspectionContexts contexts, UploadResultMapper mapper,
                               PermissionService permissions) {
        this.contexts = contexts; this.mapper = mapper; this.permissions = permissions;
    }

    public UploadResult get(long uploadId) {
        if (uploadId < 1) throw ApiException.badRequest("검사 결과 번호는 1 이상의 정수여야 합니다.", "uploadId");
        permissions.can("UI-02.upload");
        var inspection = contexts.require(uploadId);
        long existing = "B.1".equals(inspection.formType()) ? mapper.existingSalaryCount(inspection.month()) : 0;
        String notice = existing > 0
                ? inspection.month() + " 급여 " + existing + "명분이 이미 있습니다. 반영하면 새 파일로 모두 바뀝니다."
                : null;
        return new UploadResult(uploadId, inspection.formType(), inspection.month(), inspection.rowCount(),
                inspection.errorCount(), inspection.warningCount(), inspection.canApply(), existing, notice,
                "/api/uploads/" + uploadId + "/issues", inspection.normalizedRowsAvailable());
    }
}
