package com.nexusfin.gateway;

import com.nexusfin.gateway.filter.CorrelationIdGlobalFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ApiGatewayApplication.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public class ApiGatewayApplicationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("Fallback for Ledger Service returns RFC 7807 503 Service Unavailable without authentication")
    void testLedgerFallbackEndpoint() {
        webTestClient.get()
                .uri("/fallback/ledger")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().exists(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Ledger Service Temporarily Unavailable");
    }

    @Test
    @DisplayName("Fallback for Fraud Service returns RFC 7807 503 Service Unavailable without authentication")
    void testFraudFallbackEndpoint() {
        webTestClient.get()
                .uri("/fallback/fraud")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().exists(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("AI Fraud Service Temporarily Unavailable");
    }

    @Test
    @DisplayName("Propagates existing Correlation ID when provided by caller")
    void testPropagatesExistingCorrelationId() {
        String customId = "CORR-CUSTOM-9999";

        webTestClient.get()
                .uri("/fallback/ledger")
                .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, customId)
                .exchange()
                .expectHeader().valueEquals(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, customId);
    }

    @Test
    @DisplayName("Phase 2.2: Rejects unauthenticated request with 401 Unauthorized when missing Bearer token")
    void testRejectsUnauthenticatedRequest() {
        webTestClient.get()
                .uri("/api/v1/accounts")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Phase 2.2: Rejects request with invalid or tampered JWT with 401 Unauthorized")
    void testRejectsInvalidToken() {
        webTestClient.get()
                .uri("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Phase 2.2: Rejects request with expired JWT with 401 Unauthorized")
    void testRejectsExpiredToken() {
        webTestClient.get()
                .uri("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer expired-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Phase 2.2: Rejects CUSTOMER role attempting ADMIN-only endpoint (POST /api/v1/accounts) with 403 Forbidden")
    void testCustomerCannotCreateAccounts() {
        webTestClient.post()
                .uri("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer customer-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountNumber\":\"ACC-101\",\"accountType\":\"LIABILITY\",\"currency\":\"USD\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Phase 2.2: Rejects CUSTOMER role attempting SERVICE-only endpoint (POST /api/v1/fraud/evaluate) with 403 Forbidden")
    void testCustomerCannotCallFraudEvaluation() {
        webTestClient.post()
                .uri("/api/v1/fraud/evaluate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer customer-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"transactionReference\":\"TX-001\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Phase 2.2: Allows ADMIN role to access POST /api/v1/accounts (authorization passes)")
    void testAdminCanAccessCreateAccount() {
        // Authorization check must pass (not 401 or 403). Backend service is offline in gateway test so 500/503 is expected routing result.
        webTestClient.post()
                .uri("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountNumber\":\"ACC-101\",\"accountType\":\"LIABILITY\",\"currency\":\"USD\"}")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .isNotIn(401, 403));
    }

    @Test
    @DisplayName("Phase 2.2: Allows SERVICE role to access POST /api/v1/fraud/evaluate (authorization passes)")
    void testServiceCanAccessFraudEvaluation() {
        webTestClient.post()
                .uri("/api/v1/fraud/evaluate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer service-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"transactionReference\":\"TX-001\"}")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .isNotIn(401, 403));
    }
}
