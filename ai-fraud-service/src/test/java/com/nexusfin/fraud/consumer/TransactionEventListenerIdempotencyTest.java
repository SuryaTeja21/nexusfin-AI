package com.nexusfin.fraud.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.fraud.AiFraudServiceApplication;
import com.nexusfin.fraud.domain.entity.AuditLogEntry;
import com.nexusfin.fraud.dto.TransactionPostedEvent;
import com.nexusfin.fraud.repository.AuditLogRepository;
import com.nexusfin.fraud.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = AiFraudServiceApplication.class,
        properties = {
                "nexusfin.kafka.consumer.enabled=true",
                "spring.kafka.listener.auto-startup=false"
        }
)
@ActiveProfiles("test")
class TransactionEventListenerIdempotencyTest {

    @Autowired
    private TransactionEventListener transactionEventListener;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Phase 1.7: Re-delivered duplicate Kafka event must not create duplicate audit log entries")
    void duplicateKafkaEvent_mustBeDeduplicatedIdempotently() throws Exception {
        String txRef = "TX-IDEMPOTENT-" + UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        TransactionPostedEvent event = TransactionPostedEvent.builder()
                .transactionId(txId)
                .transactionReference(txRef)
                .idempotencyKey("KEY-" + UUID.randomUUID())
                .transactionType("TRANSFER")
                .amount(new BigDecimal("5000.0000"))
                .currency("USD")
                .sourceAccountNumber("ACC-1001")
                .destinationAccountNumber("ACC-2002")
                .sourceAccountId(UUID.randomUUID())
                .destinationAccountId(UUID.randomUUID())
                .description("Standard vendor payout")
                .postedAt(Instant.now())
                .build();

        String message = objectMapper.writeValueAsString(event);

        // First delivery: should process and persist audit log + processed event
        transactionEventListener.onTransactionPosted(message);

        List<AuditLogEntry> firstPass = auditLogRepository.findByTransactionReferenceOrderByCreatedAtDesc(txRef);
        assertThat(firstPass).hasSize(1);
        assertThat(processedEventRepository.existsById(txRef)).isTrue();

        // Second delivery (e.g. Kafka rebalance, at-least-once retry, redelivery)
        transactionEventListener.onTransactionPosted(message);

        // Verification: audit log must STILL contain exactly 1 entry
        List<AuditLogEntry> secondPass = auditLogRepository.findByTransactionReferenceOrderByCreatedAtDesc(txRef);
        assertThat(secondPass).hasSize(1);
        assertThat(secondPass.get(0).getId()).isEqualTo(firstPass.get(0).getId());
    }

    @Test
    @DisplayName("Phase 1.7: Kafka message with ledger outbox extra properties (totalVolume, legs) must be handled gracefully")
    void kafkaMessageWithExtraProperties_mustBeHandledGracefully() {
        String txRef = "TX-LEDGER-PAYLOAD-" + UUID.randomUUID();
        String jsonPayload = """
                {
                  "transactionId": "%s",
                  "transactionReference": "%s",
                  "transactionType": "TRANSFER",
                  "totalVolume": 75000.0000,
                  "postedAt": "%s",
                  "legs": [
                    {
                      "accountId": "%s",
                      "accountNumber": "ACC-DEBIT-01",
                      "entryType": "DEBIT",
                      "amount": 75000.0000,
                      "currency": "USD"
                    },
                    {
                      "accountId": "%s",
                      "accountNumber": "ACC-CREDIT-02",
                      "entryType": "CREDIT",
                      "amount": 75000.0000,
                      "currency": "USD"
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), txRef, Instant.now(), UUID.randomUUID(), UUID.randomUUID());

        // Process message containing outbox legs and totalVolume
        transactionEventListener.onTransactionPosted(jsonPayload);

        List<AuditLogEntry> entries = auditLogRepository.findByTransactionReferenceOrderByCreatedAtDesc(txRef);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("75000.0000"));
        assertThat(processedEventRepository.existsById(txRef)).isTrue();
    }
}
