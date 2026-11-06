package com.sicost.common.api;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final String field;

    public ApiException(int status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }
    public int status() { return status; }
    public String code() { return code; }
    public String field() { return field; }
    public static ApiException badRequest(String message, String field) {
        return new ApiException(400, "INVALID_INPUT", message, field);
    }
    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message, null);
    }
    public static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHENTICATED", "로그인이 필요합니다.", null);
    }
    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "접근 권한이 없습니다.", null);
    }
    public static ApiException unavailable() {
        return new ApiException(503, "MFA_UNAVAILABLE", "인증 메일을 보낼 수 없습니다.", null);
    }
    public static ApiException conflict(String code, String message, String field) {
        return new ApiException(409, code, message, field);
    }
}
