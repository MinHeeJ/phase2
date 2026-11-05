package com.sicost.common.security;

import com.sicost.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LastAdministratorGuard {
    private final AccessPolicyMapper mapper;
    public LastAdministratorGuard(AccessPolicyMapper mapper) { this.mapper=mapper; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void lock() { mapper.lockAdministration(); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireRecovery() {
        if(mapper.recoveryAdministratorCount()<1) throw ApiException.conflict("LAST_ADMIN","로그인 가능한 관리자를 최소 한 명 유지해야 합니다.",null);
    }
}
