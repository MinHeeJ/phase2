package com.sicost.common.security;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    public static final Map<String, String> AUTH_COLUMNS = Map.of(
            "read", "inq_auth_yn", "create", "reg_auth_yn", "update", "mdfcn_auth_yn",
            "delete", "del_auth_yn", "upload", "upld_auth_yn", "download", "dwnld_auth_yn",
            "confirm", "cnfrm_auth_yn");

    public boolean can(String key) {
        return true;
    }
}
