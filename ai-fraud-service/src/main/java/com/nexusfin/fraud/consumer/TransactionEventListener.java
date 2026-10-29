package com.nexusfin.fraud.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusfin.fraud.dto.FraudEvaluationRequest;
import com.nexusfin.fraud.dto.FraudEvaluationResponse;
import com.nexusfin.fraud.dto.TransactionPostedEvent;
import com.nexusfin.fraud.service.AiFraudDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Event-Driven Kafka Consumer for Live Ledger Transactions.
 * Asynchronously processes double-entry postings emitted by Ledger Service via Transactional Outbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexusfin.kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class TransactionEventListener {

    private final AiFraudDetectionService fraudDetectionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${nexusfin.kafka.topics.ledger-transactions:nexusfin.ledger.transactions}",
            groupId = "${spring.kafka.consumer.group-id:nexusfin-fraud-detection-group}"
    )
    public void onTransactionPosted(String message) {
        log.info("Received real-time transaction event from Kafka: {}", message);

        try {
            TransactionPostedEvent event = objectMapper.readValue(message, TransactionPostedEvent.class);

            FraudEvaluationRequest request = FraudEvaluationRequest.builder()
                    .transactionReference(event.getTransactionReference())
                    .sourceAccountId(event.getSourceAccountId())
                    .targetAccountId(event.getDestinationAccountId())
                    .accountNumber(event.getSourceAccountNumber())
                    .amount(event.getAmount())
                    .currency(event.getCurrency())
                    .description(event.getDescription() != null ? event.getDescription() : "Kafka Event: " + event.getTransactionType())
                    .clientIp("127.0.0.1")
                    .build();

            FraudEvaluationResponse response = fraudDetectionService.evaluateTransaction(request);

            log.info("Completed AI fraud evaluation for event [{}]: decision={}, riskLevel={}, score={}",
                    event.getTransactionReference(), response.getDecision(), response.getRiskLevel(), response.getRiskScore());

        } catch (Exception e) {
            log.error("Failed to process transaction event payload from Kafka: {}", e.getMessage(), e);
        }
    }
}
