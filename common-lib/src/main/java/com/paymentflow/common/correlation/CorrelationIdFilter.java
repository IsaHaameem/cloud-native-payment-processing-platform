package com.paymentflow.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes a correlation id (propagated across services) and a per-hop request id
 * for every inbound HTTP request, exposing both via SLF4J {@link MDC} so they appear
 * automatically in structured logs, and echoing the correlation id back to the caller.
 *
 * <p>The MDC is always cleared in a {@code finally} block so ids never leak across
 * pooled request threads.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = firstNonBlank(
                request.getHeader(CorrelationConstants.CORRELATION_ID_HEADER), UUID.randomUUID().toString());
        String requestId = firstNonBlank(
                request.getHeader(CorrelationConstants.REQUEST_ID_HEADER), UUID.randomUUID().toString());

        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY, correlationId);
        MDC.put(CorrelationConstants.REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(CorrelationConstants.CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.REQUEST_ID_MDC_KEY);
        }
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return StringUtils.hasText(candidate) ? candidate : fallback;
    }
}
