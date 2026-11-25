package com.nexusfin.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Reactive OAuth2 Resource Server & Edge Authorization Configuration.
 * Validates Keycloak JWT signatures, expiry, issuer, and maps realm_access.roles to ROLE_* authorities.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable) // Delegated to Spring Cloud Gateway globalcors
                .authorizeExchange(exchanges -> exchanges
                        // CORS preflight requests for local frontend dev server
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Public endpoints: health, fallback circuit breakers, documentation, SPA UI assets
                        .pathMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/fallback/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/ws",
                                "/ws/**"
                        ).permitAll()
                        .pathMatchers(
                                HttpMethod.GET,
                                "/",
                                "/index.html",
                                "/favicon.ico",
                                "/*.js",
                                "/*.css",
                                "/*.ico",
                                "/*.png",
                                "/*.svg",
                                "/assets/**",
                                "/media/**",
                                "/login",
                                "/callback",
                                "/accounts",
                                "/transfer",
                                "/feed",
                                "/fraud"
                        ).permitAll()

                        // Chart of Accounts Management:
                        // Only ADMIN can create accounts
                        .pathMatchers(HttpMethod.POST, "/api/v1/accounts").hasRole("ADMIN")
                        // ADMIN and CUSTOMER can query accounts
                        .pathMatchers(HttpMethod.GET, "/api/v1/accounts", "/api/v1/accounts/**")
                        .hasAnyRole("ADMIN", "CUSTOMER")

                        // Core Ledger & Transfers:
                        // Both CUSTOMER and ADMIN can initiate transfers and double-entry postings
                        .pathMatchers("/api/v1/ledger/**").hasAnyRole("CUSTOMER", "ADMIN")

                        // AI Fraud Detection & Auditing:
                        // SERVICE (Machine-to-Machine client credentials) and ADMIN can invoke fraud endpoints
                        .pathMatchers("/api/v1/fraud/**").hasAnyRole("SERVICE", "ADMIN")

                        // All other incoming edge requests require authentication
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(reactiveJwtAuthenticationConverter()))
                )
                .build();
    }

    @Bean
    public Converter<Jwt, Mono<AbstractAuthenticationToken>> reactiveJwtAuthenticationConverter() {
        return new ReactiveJwtAuthenticationConverterAdapter(jwt -> {
            Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
            String principalName = jwt.getClaimAsString("preferred_username");
            if (principalName == null || principalName.isBlank()) {
                principalName = jwt.getSubject();
            }
            return new JwtAuthenticationToken(jwt, authorities, principalName);
        });
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        // 1. Extract roles from realm_access.roles
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.containsKey("roles")) {
            List<String> roles = (List<String>) realmAccess.get("roles");
            for (String role : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
            }
        }

        // 2. Extract roles from resource_access.{client}.roles
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            resourceAccess.values().forEach(client -> {
                if (client instanceof Map<?, ?> clientMap && clientMap.containsKey("roles")) {
                    List<String> clientRoles = (List<String>) clientMap.get("roles");
                    for (String role : clientRoles) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                    }
                }
            });
        }

        // 3. Extract standard OAuth2 scopes
        List<String> scopes = jwt.getClaimAsStringList("scope");
        if (scopes != null) {
            for (String scope : scopes) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        }

        return authorities;
    }

    @Bean
    @ConditionalOnExpression("!'${nexusfin.security.jwt.jwk-set-uri:}'.isBlank()")
    public ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${nexusfin.security.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://keycloak:8080/realms/nexusfin}") String primaryIssuer,
            @Value("${nexusfin.security.jwt.additional-issuers:http://localhost:8083/realms/nexusfin,http://localhost:8080/realms/nexusfin}") String additionalIssuers) {
        Set<String> allowedIssuers = new HashSet<>();
        allowedIssuers.add(primaryIssuer.trim());
        for (String extra : additionalIssuers.split(",")) {
            if (!extra.isBlank()) {
                allowedIssuers.add(extra.trim());
            }
        }
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                jwt -> {
                    String iss = jwt.getIssuer() != null ? jwt.getIssuer().toString() : "";
                    return allowedIssuers.contains(iss)
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(
                                    new OAuth2Error("invalid_token", "The iss claim is not valid: " + iss, null));
                }
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
