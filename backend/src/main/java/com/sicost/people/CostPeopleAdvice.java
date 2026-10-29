package com.sicost.people;

import com.sicost.common.api.ApiError;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CostPeopleController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CostPeopleAdvice {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> application(ApiException exception, HttpServletRequest request) {
        return error(exception.status(), exception.code(), exception.getMessage(), exception.field(), request);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return error(500, "SERVER_ERROR", "서버 오류가 발생했습니다.", null, request);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message, String field,
            HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError(false, Map.of(), code, message, field,
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
