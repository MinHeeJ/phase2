package com.sicost.tax;

import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=CorporateTaxController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorporateTaxExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> error(Exception ex,HttpServletRequest request) {
        int status=500;String code="INTERNAL_ERROR",message="서버 오류가 발생했습니다.",field=null;
        if(ex instanceof ApiException api) {status=api.status();code=api.code();message=api.getMessage();field=api.field();}
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ApiError(false,Map.of(),code,message,field,
            (String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
