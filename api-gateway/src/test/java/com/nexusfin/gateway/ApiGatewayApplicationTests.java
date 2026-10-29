package com.nexusfin.gateway;

import com.nexusfin.gateway.filter.CorrelationIdGlobalFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ApiGatewayApplication.class)
@ActiveProfiles("test")
public class ApiGatewayApplicationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("Fallback for Ledger Service returns RFC 7807 503 Service Unavailable")
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
    @DisplayName("Fallback for Fraud Service returns RFC 7807 503 Service Unavailable")
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
    @DisplayName("Rejects unauthenticated request with 401 when security is enabled")
    void testRejectsUnauthorizedRequest() {
        webTestClient.get()
                .uri("/api/v1/accounts")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized Request");
    }
}
