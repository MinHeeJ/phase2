package com.sicost.staffrecommendations;

import com.sicost.common.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(assignableTypes=StaffRecommendationController.class)
public class RecommendationErrors {
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public ResponseEntity<ApiError> unavailable(Exception exception,HttpServletRequest request) {
        return ResponseEntity.status(503).body(new ApiError(false,Map.of(),"SERVICE_UNAVAILABLE","추천 원천을 조회할 수 없습니다.",null,
            (String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
