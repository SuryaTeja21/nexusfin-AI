package com.nexusfin.ledger.service;

import com.nexusfin.ledger.domain.entity.OutboxEvent;
import com.nexusfin.ledger.domain.model.OutboxStatus;
import com.nexusfin.ledger.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox Dispatcher.
 * Periodically polls pending outbox events using pessimistic locking with SKIP LOCKED
 * and dispatches them synchronously to Apache Kafka with at-least-once delivery guarantees.
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${nexusfin.kafka.topics.ledger-transactions:nexusfin.ledger.transactions}")
    private String topicName;

    @Value("${nexusfin.kafka.outbox.batch-size:50}")
    private int batchSize;

    @Value("${nexusfin.kafka.outbox.max-retries:5}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${nexusfin.kafka.outbox.poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(
                OutboxStatus.PENDING, PageRequest.of(0, batchSize));

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Dispatching {} pending outbox event(s) to Kafka topic [{}]", pendingEvents.size(), topicName);

        for (OutboxEvent event : pendingEvents) {
            try {
                // Synchronous wait ensures event publication completes within the transaction boundary
                kafkaTemplate.send(topicName, event.getAggregateId(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setProcessedAt(Instant.now());
                outboxEventRepository.save(event);
                log.info("Successfully dispatched outbox event [{}] to Kafka for aggregate [{}]",
                        event.getId(), event.getAggregateId());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("Failed to dispatch outbox event [{}] to Kafka: {}", event.getId(), e.getMessage());
                int newRetryCount = event.getRetryCount() + 1;
                event.setRetryCount(newRetryCount);
                if (newRetryCount >= maxRetries) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event [{}] exceeded maximum retries ({}). Marked as FAILED.",
                            event.getId(), maxRetries);
                }
                outboxEventRepository.save(event);
            }
        }
    }
}
