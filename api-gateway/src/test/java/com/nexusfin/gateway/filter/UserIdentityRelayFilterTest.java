package com.nexusfin.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdentityRelayFilterTest {

    private final UserIdentityRelayFilter filter = new UserIdentityRelayFilter();

    @Test
    @DisplayName("Phase 2.2: Filter strips spoofed X-User-* headers from unauthenticated or incoming client requests")
    void shouldStripSpoofedHeadersWhenUnauthenticated() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-User-Id", "hacker-uuid")
                .header("X-User-Roles", "ADMIN,ROOT")
                .header("X-User-Name", "fake-admin")
                .header("X-User-Email", "hacker@evil.com")
                .build();

        ServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwardedHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        HttpHeaders headers = forwardedHeaders.get();
        assertThat(headers).isNotNull();
        assertThat(headers.containsKey("X-User-Id")).isFalse();
        assertThat(headers.containsKey("X-User-Roles")).isFalse();
        assertThat(headers.containsKey("X-User-Name")).isFalse();
        assertThat(headers.containsKey("X-User-Email")).isFalse();
    }

    @Test
    @DisplayName("Phase 2.2: Filter extracts claims from validated JWT and injects verified X-User-* headers downstream")
    void shouldInjectVerifiedHeadersFromAuthenticatedJwt() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/ledger/transfers")
                .header("X-User-Id", "spoofed-id-will-be-overridden")
                .header("X-User-Roles", "SUPERUSER-SPOOFED")
                .build();

        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Jwt jwt = new Jwt(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", "c0000000-0000-0000-0000-000000000001",
                        "preferred_username", "customer1"
                )
        );

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),
                "customer1"
        );

        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwardedHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        HttpHeaders headers = forwardedHeaders.get();
        assertThat(headers).isNotNull();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("c0000000-0000-0000-0000-000000000001");
        assertThat(headers.getFirst("X-User-Roles")).isEqualTo("CUSTOMER");
        assertThat(headers.getFirst("X-User-Name")).isEqualTo("customer1");
    }
}
