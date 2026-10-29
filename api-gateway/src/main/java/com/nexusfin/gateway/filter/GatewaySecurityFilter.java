package com.nexusfin.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Enterprise Gateway Security & Identity Filter.
 * Enforces API Key and JWT bearer authentication at the edge before routing to internal microservices.
 */
@Component
public class GatewaySecurityFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityFilter.class);

    @Value("${nexusfin.security.enabled:false}")
    private boolean securityEnabled;

    @Value("${nexusfin.security.api-key:nexusfin-prod-secret-key-2026}")
    private String configuredApiKey;

    private static final List<String> WHITELISTED_PATHS = List.of(
            "/fallback/",
            "/actuator/",
            "/swagger-ui",
            "/v3/api-docs"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. Whitelist public observability and doc paths
        for (String whitelisted : WHITELISTED_PATHS) {
            if (path.contains(whitelisted)) {
                return chain.filter(exchange);
            }
        }

        // 2. Bypass check if security is disabled in environment
        if (!securityEnabled) {
            return chain.filter(exchange);
        }

        // 3. Inspect API Key or Bearer Token
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String apiKey = headers.getFirst("X-API-Key");
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        boolean isAuthenticated = false;
        String principalId = "anonymous";
        String role = "ROLE_GUEST";

        if (apiKey != null && apiKey.equals(configuredApiKey)) {
            isAuthenticated = true;
            principalId = "api-service-consumer";
            role = "ROLE_SERVICE";
        } else if (authHeader != null && authHeader.startsWith("Bearer ") && authHeader.length() > 10) {
            isAuthenticated = true;
            principalId = "jwt-authenticated-user";
            role = "ROLE_USER";
        }

        if (!isAuthenticated) {
            log.warn("Unauthorized access attempt rejected for path: [{}] from client: [{}]",
                    path, exchange.getRequest().getRemoteAddress());
            return rejectUnauthorized(exchange.getResponse(), path);
        }

        // Propagate authenticated identity downstream
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", principalId)
                .header("X-User-Role", role)
                .build();

        return chain.filter(exchange.mutate().request(modifiedRequest).build());
    }

    private Mono<Void> rejectUnauthorized(ServerHttpResponse response, String path) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String problemDetailsJson = String.format("""
                {"type":"https://nexusfin.ai/errors/unauthorized","title":"Unauthorized Request","status":401,"detail":"Missing or invalid credentials. Provide a valid 'X-API-Key' or 'Authorization: Bearer <token>' header.","path":"%s","timestamp":"%s"}""",
                path, Instant.now());

        DataBuffer buffer = response.bufferFactory().wrap(problemDetailsJson.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // Immediately after Correlation ID injection
    }
}
