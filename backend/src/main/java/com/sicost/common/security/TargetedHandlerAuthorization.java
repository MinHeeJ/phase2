package com.sicost.common.security;

import jakarta.servlet.http.HttpServletRequest;

/** Authorizes handlers whose screen depends on a selected business target rather than a method name. */
public interface TargetedHandlerAuthorization {
    /** Implementations must reject unknown operations and resolve permissions from server-owned targets. */
    void authorizeOperation(String operation, HttpServletRequest request);
}
