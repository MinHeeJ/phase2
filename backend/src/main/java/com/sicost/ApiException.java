package com.sicost;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final String field;
    public ApiException(int status, String code, String field, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }
    public static ApiException invalid(String field, String message) {
        return new ApiException(400, "INVALID_INPUT", field, message);
    }
    public static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", null, "대상을 찾을 수 없습니다.");
    }
}
