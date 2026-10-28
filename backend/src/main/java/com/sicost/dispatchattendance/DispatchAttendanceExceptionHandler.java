package com.sicost.dispatchattendance;

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
@RestControllerAdvice(assignableTypes = DispatchAttendanceController.class)
public class DispatchAttendanceExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> application(ApiException ex, HttpServletRequest request) {
        return error(ex.status(), ex.status() == 400 ? "VALIDATION_ERROR" : ex.code(),
            ex.getMessage(), ex.field(), request);
    }
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missing(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return error(400, "VALIDATION_ERROR", "입력값을 확인하세요.", ex.getParameterName(), request);
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> type(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return error(400, "VALIDATION_ERROR", "입력값을 확인하세요.", ex.getName(), request);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return error(400, "VALIDATION_ERROR", "입력값을 확인하세요.", null, request);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message,
            String field, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(false, Map.of(), code, message,
            field, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
