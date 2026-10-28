package com.sicost.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> application(ApiException ex, HttpServletRequest request) {
        return error(ex.status(), ex.code(), ex.getMessage(), ex.field(), request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var field = ex.getBindingResult().getFieldErrors().stream().findFirst();
        return error(400, "INVALID_INPUT", field.map(e -> e.getDefaultMessage()).orElse("입력값을 확인하세요."),
            field.map(e -> e.getField()).orElse(null), request);
    }
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missing(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return error(400, "INVALID_INPUT", "입력값을 확인하세요.", ex.getParameterName(), request);
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> type(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return error(400, "INVALID_INPUT", "입력값을 확인하세요.", ex.getName(), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        return error(400, "INVALID_INPUT", "입력값을 확인하세요.", null, request);
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        if (billingRequest(request)) {
            return error(400, "VALIDATION_ERROR", "파일은 10MB 이하만 올릴 수 있습니다.", "file", request);
        }
        return error(413, "INVALID_INPUT", "파일은 10MB 이하만 올릴 수 있습니다.", "file", request);
    }
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        return error(400, "INVALID_INPUT", "파일을 선택해 주세요.", ex.getRequestPartName(), request);
    }
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> multipart(MultipartException ex, HttpServletRequest request) {
        return error(400, "INVALID_INPUT", "올바른 파일 요청을 보내 주세요.", "file", request);
    }
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> absent(Exception ex, HttpServletRequest request) {
        return error(404, "NOT_FOUND", "요청한 항목이 없습니다.", null, request);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        Throwable root = ex.getMostSpecificCause();
        if (root instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
            return error(409, "DUPLICATE_CODE", "이미 있는 코드입니다.", null, request);
        }
        return error(500, "SERVER_ERROR", "서버 오류가 발생했습니다.", null, request);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        return error(500, "SERVER_ERROR", "서버 오류가 발생했습니다.", null, request);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message, String field, HttpServletRequest request) {
        if (status == 400 && "INVALID_INPUT".equals(code) && billingRequest(request)) {
            code = "VALIDATION_ERROR";
        }
        String id = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(false, Map.of(), code, message, field, id));
    }

    private boolean billingRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/billings") || path.startsWith("/api/billings/")
            || path.equals("/api/dispatch-attendance")
            || path.equals("/api/uploads/dispatch-attendance/validate")
            || path.equals("/api/uploads/dispatch-attendance/apply");
    }
}
