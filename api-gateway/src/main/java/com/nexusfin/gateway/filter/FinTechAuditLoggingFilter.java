package com.nexusfin.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Enterprise Audit Logging Filter for all incoming financial Gateway requests.
 * Records request method, path, remote client address, and processing latency.
 */
@Component
public class FinTechAuditLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(FinTechAuditLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);

        log.info("[GATEWAY-INBOUND] CorrelationId: {} | Method: {} | Path: {}", correlationId, method, path);

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long duration = System.currentTimeMillis() - startTime;
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            log.info("[GATEWAY-OUTBOUND] CorrelationId: {} | Status: {} | Duration: {}ms",
                    correlationId, status != null ? status.value() : "UNKNOWN", duration);
        }));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
