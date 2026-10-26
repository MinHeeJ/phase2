package com.si.cost.common.api;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String field;

    public BusinessException(HttpStatus status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getField() { return field; }
    public static BusinessException invalid(String field) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력값을 확인하세요.", field);
    }
    public static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 데이터를 찾을 수 없습니다.", null);
    }
    public static BusinessException conflict(String code, String message, String field) {
        return new BusinessException(HttpStatus.CONFLICT, code, message, field);
    }
}
