package com.nexusfin.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.gateway.filter.CorrelationIdGlobalFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ApiGatewayApplication.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityGatewayTest {

    @Autowired
    private WebTestClient webTestClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Phase 6.1: API Gateway exposes /actuator/prometheus without authentication and emits HTTP histogram buckets")
    void exposesPrometheusEndpointWithHistogramBuckets() {
        // Trigger one request first so http_server_requests_seconds_bucket is populated
        webTestClient.get()
                .uri("/fallback/ledger")
                .exchange()
                .expectStatus().isEqualTo(503);

        String scrape = webTestClient.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(scrape).isNotNull();
        assertThat(scrape).contains("http_server_requests_seconds_bucket");
        assertThat(scrape).contains("application=\"api-gateway-test\"");
    }

    @Test
    @DisplayName("Phase 6.3 & 6.4: Propagates X-Correlation-Id as baggage & MDC into structured JSON logs with traceId/spanId")
    void logsStructuredJsonWithTraceAndCorrelationMdc(CapturedOutput output) throws Exception {
        String customCorrelationId = "CORR-GW-OBS-2026";

        // Hit a Gateway route (matches test-accounts-route -> executes CorrelationIdGlobalFilter + FinTechAuditLoggingFilter)
        webTestClient.get()
                .uri("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, customCorrelationId)
                .exchange()
                .expectHeader().valueEquals(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, customCorrelationId);

        JsonNode matchingLog = findJsonLogContaining(output.getOut(), customCorrelationId);
        assertThat(matchingLog)
                .withFailMessage("Expected structured JSON log containing correlation id [%s], output was:\n%s",
                        customCorrelationId, output.getOut())
                .isNotNull();
        assertThat(matchingLog.path("service").asText()).isEqualTo("api-gateway-test");
        assertThat(matchingLog.path("X-Correlation-Id").asText()).isEqualTo(customCorrelationId);
        assertThat(matchingLog.path("traceId").asText()).matches("[0-9a-f]{32}");
        assertThat(matchingLog.path("spanId").asText()).matches("[0-9a-f]{16}");
    }

    private JsonNode findJsonLogContaining(String stdout, String needle) throws Exception {
        for (String line : stdout.split("\\R")) {
            if (line.startsWith("{") && line.contains(needle)) {
                return objectMapper.readTree(line);
            }
        }
        return null;
    }
}
