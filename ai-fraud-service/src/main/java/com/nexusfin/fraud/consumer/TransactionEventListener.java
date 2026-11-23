package com.nexusfin.fraud.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.fraud.domain.entity.ProcessedEvent;
import com.nexusfin.fraud.dto.FraudEvaluationRequest;
import com.nexusfin.fraud.dto.FraudEvaluationResponse;
import com.nexusfin.fraud.dto.TransactionPostedEvent;
import com.nexusfin.fraud.repository.ProcessedEventRepository;
import com.nexusfin.fraud.service.AiFraudDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Event-Driven Kafka Consumer for Live Ledger Transactions.
 * Asynchronously processes double-entry postings emitted by Ledger Service via Transactional Outbox.
 * Idempotency guaranteed via processed_events deduplication store.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexusfin.kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class TransactionEventListener {

    private final AiFraudDetectionService fraudDetectionService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(
            topics = "${nexusfin.kafka.topics.ledger-transactions:nexusfin.ledger.transactions}",
            groupId = "${spring.kafka.consumer.group-id:nexusfin-fraud-detection-group}"
    )
    public void onTransactionPosted(String message) {
        log.info("Received real-time transaction event from Kafka: {}", message);

        try {
            TransactionPostedEvent event = objectMapper.readValue(message, TransactionPostedEvent.class);

            String eventId = event.getTransactionReference() != null && !event.getTransactionReference().isBlank()
                    ? event.getTransactionReference()
                    : (event.getTransactionId() != null ? event.getTransactionId().toString() : null);

            if (eventId == null || eventId.isBlank()) {
                log.warn("Transaction event received without reference or transactionId. Skipping.");
                return;
            }

            if (processedEventRepository.existsById(eventId)) {
                log.info("Duplicate transaction event detected for eventId [{}]. Skipping processing to ensure consumer idempotency.", eventId);
                return;
            }

            java.util.UUID sourceAccountId = event.getSourceAccountId();
            java.util.UUID targetAccountId = event.getDestinationAccountId();
            if (event.getLegs() != null) {
                for (java.util.Map<String, Object> leg : event.getLegs()) {
                    try {
                        String entryType = String.valueOf(leg.get("entryType"));
                        if ("DEBIT".equalsIgnoreCase(entryType) && sourceAccountId == null && leg.get("accountId") != null) {
                            sourceAccountId = java.util.UUID.fromString(String.valueOf(leg.get("accountId")));
                        } else if ("CREDIT".equalsIgnoreCase(entryType) && targetAccountId == null && leg.get("accountId") != null) {
                            targetAccountId = java.util.UUID.fromString(String.valueOf(leg.get("accountId")));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if (sourceAccountId == null) {
                sourceAccountId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
            }
            if (targetAccountId == null) {
                targetAccountId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
            }

            FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                    .transactionReference(event.getTransactionReference())
                    .sourceAccountId(sourceAccountId)
                    .targetAccountId(targetAccountId)
                    .accountNumber(event.getSourceAccountNumber())
                    .amount(event.getEffectiveAmount())
                    .currency(event.getCurrency() != null ? event.getCurrency() : "USD")
                    .description(event.getDescription() != null ? event.getDescription() : "Kafka Event: " + event.getTransactionType())
                    .clientIp("127.0.0.1")
                    .build();

            FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

            // Record processed event to guarantee idempotent consumption
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .eventType(event.getTransactionType() != null ? event.getTransactionType() : "TRANSACTION_POSTED")
                    .processedAt(Instant.now())
                    .build());

            log.info("Completed AI fraud evaluation for event [{}]: decision={}, riskLevel={}, score={}",
                    event.getTransactionReference(), response.getDecision(), response.getRiskLevel(), response.getRiskScore());

        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate event detected via DB constraint violation: {}", e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Malformed JSON payload received from Kafka: {}", e.getMessage());
            throw new IllegalArgumentException("Malformed JSON payload: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Failed to process transaction event payload from Kafka: {}", e.getMessage(), e);
            throw e;
        }
    }
}
