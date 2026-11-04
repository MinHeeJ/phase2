package com.sicost.dashboardops;

import com.sicost.common.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DashboardAuditService {
    private final DashboardAuditMapper mapper;
    private final CurrentUser user;
    public DashboardAuditService(DashboardAuditMapper mapper,CurrentUser user) { this.mapper=mapper; this.user=user; }

    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    public void record(String filename,String description) {
        if (mapper.download(new DashboardAuditMapper.Download(filename,description,user.userId()))!=1)
            throw new IllegalStateException("다운로드 감사 기록에 실패했습니다.");
    }
}
