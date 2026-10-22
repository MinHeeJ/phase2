package com.sicost.common.api;

import java.util.Map;

public record ApiError(boolean success, Map<String, Object> meta, String code,
        String message, String field, String requestId) {
    public static ApiError of(String code, String message, String field, String requestId) {
        return new ApiError(false, Map.of(), code, message, field, requestId);
    }
}
