package com.nexusfin.ledger.filter;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Propagates X-Correlation-Id across incoming HTTP headers, Micrometer Tracing baggage,
 * HTTP response headers, and SLF4J MDC for structured JSON logging.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationIdMdcFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final Tracer tracer;

    @Autowired
    public CorrelationIdMdcFilter(@Autowired(required = false) Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        MDC.put(CORRELATION_ID_HEADER, correlationId);

        BaggageInScope baggageScope = null;
        if (tracer != null) {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                currentSpan.tag("correlation.id", correlationId);
                baggageScope = tracer.createBaggageInScope(currentSpan.context(), CORRELATION_ID_HEADER, correlationId);
            } else {
                baggageScope = tracer.createBaggageInScope(CORRELATION_ID_HEADER, correlationId);
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (baggageScope != null) {
                baggageScope.close();
            }
            MDC.remove(CORRELATION_ID_HEADER);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String headerVal = request.getHeader(CORRELATION_ID_HEADER);
        if (headerVal != null && !headerVal.isBlank()) {
            return headerVal.trim();
        }
        if (tracer != null) {
            Baggage baggage = tracer.getBaggage(CORRELATION_ID_HEADER);
            String baggageVal = baggage != null ? baggage.get() : null;
            if (baggageVal != null && !baggageVal.isBlank()) {
                return baggageVal;
            }
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null && currentSpan.context() != null) {
                return currentSpan.context().traceId();
            }
        }
        return UUID.randomUUID().toString();
    }
}
