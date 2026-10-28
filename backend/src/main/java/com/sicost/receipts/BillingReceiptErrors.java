package com.sicost.receipts;

import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = BillingReceiptController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BillingReceiptErrors {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> application(ApiException ex, HttpServletRequest request) {
        String code = ex.status() == 400 ? "VALIDATION_ERROR" : ex.code();
        return ResponseEntity.status(ex.status()).body(new ApiError(false, Map.of(), code,
            ex.getMessage(), ex.field(), (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> input(Exception ex, HttpServletRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException type ? type.getName() : null;
        return application(com.sicost.common.validation.BillingRules.invalid(field), request);
    }
}
