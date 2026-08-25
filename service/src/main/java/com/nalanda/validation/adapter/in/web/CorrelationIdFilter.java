package com.nalanda.validation.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id in the SLF4J MDC for the life of the request so every log line of a
 * request can be tied together (research.md R-011). Request bodies are never logged.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        MDC.put(CORRELATION_ID_MDC_KEY, resolveCorrelationId(request));
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        var providedCorrelationId = request.getHeader(CORRELATION_ID_HEADER);
        if (providedCorrelationId == null || providedCorrelationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return providedCorrelationId;
    }
}
