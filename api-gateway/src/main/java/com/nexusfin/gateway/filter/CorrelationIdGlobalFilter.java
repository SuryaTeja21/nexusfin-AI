package com.nexusfin.gateway.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.reactor.ReactorBaggage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Ensures every incoming transaction and API request carries an immutable
 * distributed tracing Correlation ID (X-Correlation-Id) across all downstream services,
 * OpenTelemetry trace baggage, and SLF4J MDC structured JSON logs.
 */
@Component
public class CorrelationIdGlobalFilter implements WebFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final Tracer tracer;

    public CorrelationIdGlobalFilter() {
        this.tracer = null;
    }

    @Autowired
    public CorrelationIdGlobalFilter(@Autowired(required = false) Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String existingCorrelationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        final String correlationId = (existingCorrelationId != null && !existingCorrelationId.isBlank())
                ? existingCorrelationId
                : UUID.randomUUID().toString();

        // Mutate response to return Correlation ID back to the client
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        // Mutate request to ensure downstream microservices receive this header
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(modifiedRequest).build();

        return Mono.defer(() -> {
            if (tracer != null) {
                Span currentSpan = tracer.currentSpan();
                if (currentSpan != null) {
                    currentSpan.tag("correlation.id", correlationId);
                }
            }
            return chain.filter(mutatedExchange);
        }).contextWrite(ReactorBaggage.append(CORRELATION_ID_HEADER, correlationId));
    }

    @Override
    public int getOrder() {
        // Run immediately after Spring's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1)
        // so that the HTTP server span is active when attaching X-Correlation-Id baggage.
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
