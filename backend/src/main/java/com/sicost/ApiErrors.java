package com.sicost;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> domain(ApiException e, HttpServletRequest request) {
        return error(e.status, e.code, e.field, e.getMessage(), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> invalid(Exception e, HttpServletRequest request) {
        String field = "body";
        if (e instanceof MissingServletRequestParameterException missing) field = missing.getParameterName();
        if (e.getCause() instanceof UnrecognizedPropertyException unknown) field = unknown.getPropertyName();
        return error(400, "INVALID_INPUT", field, "입력값을 확인하세요.", request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        return error(500, "SERVER_ERROR", null, "처리 중 오류가 발생했습니다. 문의 번호를 알려 주세요.", request);
    }
    private ResponseEntity<Map<String, Object>> error(int status, String code, String field, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false); body.put("meta", Map.of()); body.put("code", code);
        body.put("field", field); body.put("message", message);
        body.put("requestId", RequestTrace.currentId());
        return ResponseEntity.status(status).body(body);
    }
}
