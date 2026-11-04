package com.sicost.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiError;
import com.sicost.common.api.ApiException;
import com.sicost.common.api.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

public class AiEnvironmentFilter extends OncePerRequestFilter {
    private final AiAccessGuard access;
    private final ObjectMapper json;
    public AiEnvironmentFilter(AiAccessGuard access, ObjectMapper json) { this.access = access; this.json = json; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/ai/") && !path.equals("/api/ai");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { access.requireDevelopment(); }
        catch (ApiException ex) {
            response.setStatus(ex.status()); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getOutputStream(), new ApiError(false, Map.of(), ex.code(), ex.getMessage(), ex.field(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
            return;
        }
        chain.doFilter(request, response);
    }
}
