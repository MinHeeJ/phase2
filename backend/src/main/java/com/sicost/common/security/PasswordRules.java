package com.sicost.common.security;

import com.sicost.common.api.ApiException;
import java.nio.charset.StandardCharsets;

public final class PasswordRules {
    private PasswordRules() {}
    public static void validate(String password,String confirmation) {
        if(password==null || password.length()<8 || password.getBytes(StandardCharsets.UTF_8).length>72
            || !password.matches("(?s).*[A-Za-z].*") || !password.matches("(?s).*[0-9].*")
            || !password.matches("(?s).*[^A-Za-z0-9\\s].*")) {
            throw ApiException.badRequest("8자 이상 영문·숫자·특수문자를 포함하세요.","newPassword");
        }
        if(!password.equals(confirmation)) throw ApiException.badRequest("비밀번호 확인이 일치하지 않습니다.","confirmPassword");
    }
}
