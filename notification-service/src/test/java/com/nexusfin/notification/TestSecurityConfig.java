package com.nexusfin.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public JwtDecoder jwtDecoder() {
        return token -> {
            if ("invalid-token".equals(token)) {
                throw new BadJwtException("Invalid token signature");
            }
            if ("expired-token".equals(token)) {
                throw new BadJwtException("Token has expired");
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
            } else if ("customer2-token".equals(token)) {
                claims = Map.of(
                        "sub", "c0000000-0000-0000-0000-000000000002",
                        "preferred_username", "customer2",
                        "email", "customer2@nexusfin.io",
                        "realm_access", Map.of("roles", List.of("CUSTOMER")),
                        "exp", Instant.now().plusSeconds(3600),
                        "iat", Instant.now()
                );
            } else {
                // Default customer1 token
                claims = Map.of(
                        "sub", "c0000000-0000-0000-0000-000000000001",
                        "preferred_username", "customer1",
                        "email", "customer1@nexusfin.io",
                        "realm_access", Map.of("roles", List.of("CUSTOMER")),
                        "exp", Instant.now().plusSeconds(3600),
                        "iat", Instant.now()
                );
            }

            return new Jwt(token, Instant.now(), Instant.now().plusSeconds(3600), headers, claims);
        };
    }
}
