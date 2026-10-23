package com.sicost.common.api;

import java.util.Map;

public record ApiError(boolean success, Map<String, Object> meta, String code,
                       String message, String field, String requestId) {}
