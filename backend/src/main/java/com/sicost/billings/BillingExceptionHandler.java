package com.sicost.billings;

import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(-10)
@RestControllerAdvice(basePackages = {"com.sicost.billings", "com.sicost.attendance",
    "com.sicost.billinggaps", "com.sicost.receivables", "com.sicost.dispatchattendance", "com.sicost.billingsops"})
public class BillingExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> application(ApiException ex, HttpServletRequest request) {
        return error(ex.status(), ex.status() == 400 && "INVALID_INPUT".equals(ex.code())
            ? "VALIDATION_ERROR" : ex.code(), ex.getMessage(), ex.field(), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
            : ex instanceof MissingServletRequestParameterException missing ? missing.getParameterName() : null;
        return error(400, "VALIDATION_ERROR", "입력값을 확인하세요.", field, request);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message, String field,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(false, Map.of(), code, message,
            field, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
