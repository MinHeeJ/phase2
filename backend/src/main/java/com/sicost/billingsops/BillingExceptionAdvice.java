package com.sicost.billingsops;

import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(-10)
@RestControllerAdvice(basePackages = {"com.sicost.billingsops", "com.sicost.billings", "com.sicost.dispatchattendance"})
public class BillingExceptionAdvice {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> application(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status()).body(new ApiError(false, Map.of(),
            ex.status() == 400 ? "VALIDATION_ERROR" : ex.code(), ex.getMessage(), ex.field(),
            (String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError(false, Map.of(), "VALIDATION_ERROR",
            "입력값을 확인하세요.", null, (String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
