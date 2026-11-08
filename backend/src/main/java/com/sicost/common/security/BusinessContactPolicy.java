package com.sicost.common.security;

import org.springframework.stereotype.Service;
import static com.sicost.common.security.SecurityModels.*;
import static com.sicost.common.security.SecurityAuditService.*;

@Service
public class BusinessContactPolicy {
    public enum Target { DEVELOPER, PARTNER, ACCOUNT }
    private final AuthorizationService authorization;
    public BusinessContactPolicy(AuthorizationService authorization) { this.authorization=authorization; }
    public void require(Target target,Resource row,Purpose purpose,boolean active,boolean rawDownload) {
        String screen=switch(target) { case DEVELOPER->"UI-02";case PARTNER->"UI-15";case ACCOUNT->"UI-23"; };
        var scope=authorization.requireRow(screen+(rawDownload?".download":".read"),row);
        if(!active || purpose==null || scope.grants().stream().noneMatch(g->g.contactInquiry() &&
            (!rawDownload || g.contactDownload()) && scope.allows(g,row) && permitted(g,target,purpose))) throw AuthorizationService.forbidden();
    }
    private boolean permitted(Permission grant,Target target,Purpose purpose) {
        return switch(grant.roleName()) {
            case "시스템 관리자" -> target==Target.ACCOUNT && purpose==Purpose.ACCOUNT_CORRECTION && grant.admin();
            case "영업 파트장" -> grant.scope()==DataScope.PART &&
                ((target==Target.DEVELOPER && purpose==Purpose.STAFF_OPERATION) || (target==Target.PARTNER && purpose==Purpose.CONTRACT_SALES));
            case "일반 사용자(PM)" -> target==Target.DEVELOPER && grant.scope()==DataScope.PROJECT && purpose==Purpose.STAFF_OPERATION;
            case "재무 담당" -> target==Target.PARTNER && (purpose==Purpose.BILLING || purpose==Purpose.RECEIPT || purpose==Purpose.PAYMENT);
            default -> false;
        };
    }
}
