package com.nexusfin.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.ledger.domain.entity.OutboxEvent;
import com.nexusfin.ledger.domain.model.AccountType;
import com.nexusfin.ledger.domain.model.EntryType;
import com.nexusfin.ledger.domain.model.OutboxStatus;
import com.nexusfin.ledger.domain.model.TransactionType;
import com.nexusfin.ledger.dto.AccountResponse;
import com.nexusfin.ledger.dto.CreateAccountRequest;
import com.nexusfin.ledger.dto.PostTransactionRequest;
import com.nexusfin.ledger.dto.TransactionLegRequest;
import com.nexusfin.ledger.repository.OutboxEventRepository;
import com.nexusfin.ledger.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LedgerServiceApplication.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityLedgerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private String assetAcc;
    private String liabAcc;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AccountResponse asset = accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber("ACC-OBS-A-" + suffix)
                .accountName("Obs Asset")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .initialBalance(new BigDecimal("10000.0000"))
                .build());
        AccountResponse liab = accountService.createAccount(CreateAccountRequest.builder()
                .accountNumber("ACC-OBS-L-" + suffix)
                .accountName("Obs Liability")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("1000.0000"))
                .build());
        assetAcc = asset.getAccountNumber();
        liabAcc = liab.getAccountNumber();
    }

    @Test
    @DisplayName("Phase 6.1: /actuator/prometheus is permitted without auth and reports outbox_pending_events gauge & latency histograms")
    void prometheusExposesOutboxBacklogAndHistograms() throws Exception {
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType("TRANSACTION")
                .aggregateId("TX-PROM-" + UUID.randomUUID())
                .eventType("TRANSACTION_POSTED")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build());

        // Trigger at least one completed HTTP request so http_server_requests_seconds_bucket is populated
        mockMvc.perform(get("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("outbox_pending_events{application=\"ledger-service-test\",} 1.0");
        assertThat(body).contains("http_server_requests_seconds_bucket");
        assertThat(body).contains("idempotency_cache_hit_total");
    }

    @Test
    @DisplayName("Phase 6.3 & 6.4: Propagates X-Correlation-Id and traceParent to OutboxEvent and structured JSON logs")
    void propagatesCorrelationIdAndTraceToOutboxAndLogs(CapturedOutput output) throws Exception {
        String correlationId = "CORR-LEDGER-OBS-" + UUID.randomUUID();
        String txRef = "TX-OBS-" + UUID.randomUUID().toString().substring(0, 8);

        PostTransactionRequest request = PostTransactionRequest.builder()
                .transactionReference(txRef)
                .idempotencyKey(UUID.randomUUID().toString())
                .transactionType(TransactionType.TRANSFER)
                .description("Observability trace propagation test")
                .legs(List.of(
                        TransactionLegRequest.builder()
                                .accountNumber(assetAcc)
                                .entryType(EntryType.DEBIT)
                                .amount(new BigDecimal("50.0000"))
                                .currency("USD")
                                .build(),
                        TransactionLegRequest.builder()
                                .accountNumber(liabAcc)
                                .entryType(EntryType.CREDIT)
                                .amount(new BigDecimal("50.0000"))
                                .currency("USD")
                                .build()
                ))
                .build();

        mockMvc.perform(post("/api/v1/ledger/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-Id", correlationId));

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        assertThat(event.getTraceParent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");

        JsonNode jsonLog = findJsonLogContaining(output.getOut(), txRef);
        assertThat(jsonLog).isNotNull();
        assertThat(jsonLog.path("service").asText()).isEqualTo("ledger-service-test");
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
