package com.si.cost.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException ex, HttpServletRequest request) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getField(), request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var error = ex.getBindingResult().getFieldErrors().stream().findFirst();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력값을 확인하세요.",
                error.map(org.springframework.validation.FieldError::getField).orElse(null), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ApiError> badRequest(Exception ex, HttpServletRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : ex instanceof MissingServletRequestParameterException missing ? missing.getParameterName() : null;
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력값을 확인하세요.", field, request);
    }
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> notFound(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 데이터를 찾을 수 없습니다.", null, request);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "처리 중 오류가 발생했습니다.", null, request);
    }
    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
                                             String field, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(code, message, field,
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
