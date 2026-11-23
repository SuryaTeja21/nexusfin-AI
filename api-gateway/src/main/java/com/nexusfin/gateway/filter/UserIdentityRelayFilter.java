package com.nexusfin.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Edge Identity Relay Filter.
 * 1. Strips any incoming spoofed X-User-* headers from untrusted external clients.
 * 2. Extracts authenticated claims from the validated JWT token in SecurityContext.
 * 3. Injects cryptographically verified X-User-Id, X-User-Roles, and X-User-Name downstream.
 */
@Component
public class UserIdentityRelayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UserIdentityRelayFilter.class);

    private static final List<String> SPOOFABLE_HEADERS = List.of(
            "X-User-Id",
            "X-User-Roles",
            "X-User-Role",
            "X-User-Name",
            "X-User-Email"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(jwtAuth -> {
                    Jwt jwt = jwtAuth.getToken();
                    String userId = jwt.getSubject();
                    String username = jwt.getClaimAsString("preferred_username");
                    if (username == null || username.isBlank()) {
                        username = userId;
                    }

                    String roles = jwtAuth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .filter(auth -> auth.startsWith("ROLE_"))
                            .map(auth -> auth.substring(5))
                            .collect(Collectors.joining(","));

                    ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();

                    // Strip any potentially spoofed client headers first
                    for (String header : SPOOFABLE_HEADERS) {
                        requestBuilder.headers(httpHeaders -> httpHeaders.remove(header));
                    }

                    // Propagate verified identity headers to internal microservices
                    if (userId != null && !userId.isBlank()) {
                        requestBuilder.header("X-User-Id", userId);
                    }
                    if (!roles.isBlank()) {
                        requestBuilder.header("X-User-Roles", roles);
                    }
                    if (username != null && !username.isBlank()) {
                        requestBuilder.header("X-User-Name", username);
                    }

                    return exchange.mutate().request(requestBuilder.build()).build();
                })
                .defaultIfEmpty(stripSpoofedHeaders(exchange))
                .flatMap(chain::filter);
    }

    private ServerWebExchange stripSpoofedHeaders(ServerWebExchange exchange) {
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();
        for (String header : SPOOFABLE_HEADERS) {
            requestBuilder.headers(httpHeaders -> httpHeaders.remove(header));
        }
        return exchange.mutate().request(requestBuilder.build()).build();
    }

    @Override
    public int getOrder() {
        // Run after Spring Security authentication has populated SecurityContext, before Netty routing
        return 0;
    }
}
