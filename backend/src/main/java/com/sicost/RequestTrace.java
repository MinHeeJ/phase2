package com.sicost;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestTrace extends OncePerRequestFilter {
    private static final ThreadLocal<String> CURRENT_ID = new ThreadLocal<>();
    static String currentId() { return CURRENT_ID.get(); }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", id);
        CURRENT_ID.set(id);
        try {
            chain.doFilter(request, response);
        } finally {
            CURRENT_ID.remove();
        }
    }
}
