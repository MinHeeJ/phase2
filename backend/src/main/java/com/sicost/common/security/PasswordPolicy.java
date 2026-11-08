package com.sicost.common.security;

import com.sicost.common.api.ApiException;

public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static void validate(String password) {
        if(password==null || password.length()<8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 ||
            !password.matches("(?s).*\\p{L}.*") || !password.matches("(?s).*\\p{N}.*") || !password.matches("(?s).*[^\\p{L}\\p{N}\\s].*"))
            throw ApiException.badRequest("비밀번호는 8자 이상이며 문자·숫자·특수문자를 포함해야 합니다.","newPassword");
    }
}
