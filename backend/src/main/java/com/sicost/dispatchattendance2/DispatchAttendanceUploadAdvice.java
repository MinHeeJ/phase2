package com.sicost.dispatchattendance2;

import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=DispatchAttendanceUploadController.class)
public class DispatchAttendanceUploadAdvice extends ApiExceptionHandler {
    private ResponseEntity<ApiError> translate(ResponseEntity<ApiError> response) {
        ApiError body=response.getBody();
        if (response.getStatusCode().value()!=400 || body==null || !"INVALID_INPUT".equals(body.code())) return response;
        return ResponseEntity.badRequest().body(new ApiError(false,body.meta(),"VALIDATION_ERROR",
            body.message(),body.field(),body.requestId()));
    }
    @Override public ResponseEntity<ApiError> application(ApiException ex, HttpServletRequest request) {
        return translate(super.application(ex,request));
    }
    @Override public ResponseEntity<ApiError> missing(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return translate(super.missing(ex,request));
    }
    @Override public ResponseEntity<ApiError> type(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return translate(super.type(ex,request));
    }
    @Override public ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        return translate(super.malformed(ex,request));
    }
    @Override public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        return translate(super.missingPart(ex,request));
    }
    @Override public ResponseEntity<ApiError> multipart(MultipartException ex, HttpServletRequest request) {
        return translate(super.multipart(ex,request));
    }
}
