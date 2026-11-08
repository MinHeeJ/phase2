package com.sicost.common.security;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import com.sicost.common.api.ApiException;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    public static final Map<String, String> AUTH_COLUMNS = Map.of(
        "read", "inq_auth_yn", "create", "reg_auth_yn", "update", "mdfcn_auth_yn",
        "delete", "del_auth_yn", "upload", "upld_auth_yn", "download", "dwnld_auth_yn",
        "confirm", "cnfrm_auth_yn");

    private AuthorizationService authorization;
    @Autowired public void setAuthorization(AuthorizationService authorization) { this.authorization=authorization; }
    public boolean can(String key) {
        if(authorization==null) {
            new CurrentUser().principal();
            throw new ApiException(403,"FORBIDDEN","접근 권한이 없습니다.",null);
        }
        authorization.require(key);
        return true;
    }
}
