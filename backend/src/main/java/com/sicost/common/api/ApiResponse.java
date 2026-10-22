package com.sicost.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record ApiResponse<T>(boolean success, Map<String, Object> meta,
        @JsonInclude(JsonInclude.Include.NON_NULL) T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, Map.of(), data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }
}
