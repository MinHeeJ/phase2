package com.sicost.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, Map<String, Object> meta, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, Map.of(), data); }
    public static ApiResponse<Void> ok() { return new ApiResponse<>(true, Map.of(), null); }
}
