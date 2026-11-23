package com.nexusfin.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        return token -> {
            if ("invalid-token".equals(token)) {
                return Mono.error(new BadJwtException("Invalid token signature"));
            }
            if ("expired-token".equals(token)) {
                return Mono.error(new BadJwtException("Token has expired"));
            }

            Map<String, Object> headers = Map.of("alg", "RS256", "typ", "JWT");
            Map<String, Object> claims;

            if ("admin-token".equals(token)) {
                claims = Map.of(
                        "sub", "a0000000-0000-0000-0000-000000000001",
                        "preferred_username", "admin1",
                        "email", "admin1@nexusfin.io",
                        "realm_access", Map.of("roles", List.of("ADMIN")),
                        "exp", Instant.now().plusSeconds(3600),
                        "iat", Instant.now()
                );
            } else if ("service-token".equals(token)) {
                claims = Map.of(
                        "sub", "s0000000-0000-0000-0000-000000000001",
                        "preferred_username", "service-account-nexusfin-service",
                        "realm_access", Map.of("roles", List.of("SERVICE")),
                        "exp", Instant.now().plusSeconds(3600),
                        "iat", Instant.now()
                );
            } else {
                // Default customer token
                claims = Map.of(
                        "sub", "c0000000-0000-0000-0000-000000000001",
                        "preferred_username", "customer1",
                        "email", "customer1@nexusfin.io",
                        "realm_access", Map.of("roles", List.of("CUSTOMER")),
                        "exp", Instant.now().plusSeconds(3600),
                        "iat", Instant.now()
                );
            }

            Jwt jwt = new Jwt(token, Instant.now(), Instant.now().plusSeconds(3600), headers, claims);
            return Mono.just(jwt);
        };
    }
}
