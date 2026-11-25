package com.nexusfin.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.fraud.dto.DltMessage;
import com.nexusfin.fraud.service.DltMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AiFraudServiceApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityFraudTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DltMessageStore dltMessageStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Phase 6.1: Fraud service exposes /actuator/prometheus with DLT active gauge and latency histograms")
    void exposesPrometheusWithDltGauge() throws Exception {
        String dltId = UUID.randomUUID().toString();
        dltMessageStore.addMessage(DltMessage.builder()
                .id(dltId)
                .originalTopic("nexusfin.ledger.transactions")
                .partition(0)
                .offset(1L)
                .payload("{bad}")
                .quarantinedAt(Instant.now())
                .build());

        try {
            mockMvc.perform(get("/api/v1/fraud/status"))
                    .andExpect(status().isOk());

            String scrape = mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(scrape).contains("dlt_quarantined_active{application=\"ai-fraud-service-test\",} 1.0");
            assertThat(scrape).contains("http_server_requests_seconds_bucket");
        } finally {
            dltMessageStore.remove(dltId);
        }
    }

    @Test
    @DisplayName("Phase 6.3 & 6.4: Propagates X-Correlation-Id header & trace MDC into structured JSON logs")
    void propagatesCorrelationAndTraceToLogs(CapturedOutput output) throws Exception {
        String correlationId = "CORR-FRAUD-OBS-" + UUID.randomUUID();
        String evalPayload = String.format("""
                {
                  "transactionReference": "TX-OBS-FRAUD-%s",
                  "sourceAccountId": "%s",
                  "targetAccountId": "%s",
                  "amount": 100.00,
                  "currency": "USD",
                  "description": "Observability log verification"
                }
                """, UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/fraud/evaluate")
                        .header("X-Correlation-Id", correlationId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(evalPayload))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", correlationId));

        JsonNode jsonLog = findJsonLogContaining(output.getOut(), correlationId);
        assertThat(jsonLog).isNotNull();
        assertThat(jsonLog.path("service").asText()).isEqualTo("ai-fraud-service-test");
        assertThat(jsonLog.path("X-Correlation-Id").asText()).isEqualTo(correlationId);
        assertThat(jsonLog.path("traceId").asText()).matches("[0-9a-f]{32}");
        assertThat(jsonLog.path("spanId").asText()).matches("[0-9a-f]{16}");
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
