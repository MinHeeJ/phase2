package com.sicost.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> business(ApiException error, HttpServletRequest request) {
        return response(error.status(), error.code(), error.getMessage(), error.field(), request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> binding(BindException error, HttpServletRequest request) {
        var field = error.getBindingResult().getFieldError();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                field == null ? "입력값을 확인하세요." : field.getDefaultMessage(),
                field == null ? null : field.getField(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missing(MissingServletRequestParameterException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "필수 값을 입력하세요.", error.getParameterName(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> type(MethodArgumentTypeMismatchException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력 형식을 확인하세요.", error.getName(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ApiError> invalid(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력값을 확인하세요.", null, request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> absent(Exception error, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.", null, request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> duplicate(DuplicateKeyException error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DUPLICATE_CODE", "이미 있는 코드입니다.", null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception error, HttpServletRequest request) {
        LOG.error("requestId={} errorType={}", RequestIdFilter.requestId(request), error.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다. 다시 시도해 주세요.", null, request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
            String field, HttpServletRequest request) {
        String requestId = RequestIdFilter.requestId(request);
        return ResponseEntity.status(status).header(RequestIdFilter.HEADER, requestId)
                .body(ApiError.of(code, message, field, requestId));
    }
}
