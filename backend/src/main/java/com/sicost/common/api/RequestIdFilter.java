package com.sicost.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = UUID.randomUUID().toString();
        if (incoming != null && !incoming.isEmpty() && incoming.length() <= 64) {
            String safe = incoming.replaceAll("[\\r\\n]+|[^A-Za-z0-9_-]+", "");
            if (safe.equals(incoming)) requestId = safe;
        }
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            LOG.info("requestId={} status={}", requestId, response.getStatus());
        }
    }

    public static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id == null ? UUID.randomUUID().toString() : id.toString();
    }
}
