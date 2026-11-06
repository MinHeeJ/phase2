package com.sicost.common.security;

import com.sicost.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LastAdministratorGuard {
    private final SecurityPermissionMapper mapper;
    public LastAdministratorGuard(SecurityPermissionMapper mapper) { this.mapper=mapper; }
    /** Must wrap the entire account/role/menu reduction, audit and session invalidation. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void protect(Runnable mutation) {
        mapper.lockAdministrator();
        mutation.run();
        if (mapper.recoverableAdministrators() < 1)
            throw ApiException.conflict("LAST_ADMIN","로그인 가능한 관리자를 한 명 이상 유지해야 합니다.",null);
    }
}
