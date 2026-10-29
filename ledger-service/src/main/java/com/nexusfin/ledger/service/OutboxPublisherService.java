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

/**
 * Transactional Outbox Dispatcher.
 * Periodically polls pending outbox events and dispatches them to Apache Kafka with at-least-once delivery guarantees.
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexusfin.kafka.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${nexusfin.kafka.topics.ledger-transactions:nexusfin.ledger.transactions}")
    private String topicName;

    @Scheduled(fixedDelayString = "${nexusfin.kafka.outbox.poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(
                OutboxStatus.PENDING, PageRequest.of(0, 50));

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Dispatching {} pending outbox event(s) to Kafka topic [{}]", pendingEvents.size(), topicName);

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(topicName, event.getAggregateId(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                event.setStatus(OutboxStatus.PUBLISHED);
                                event.setProcessedAt(Instant.now());
                                outboxEventRepository.save(event);
                                log.info("Successfully dispatched outbox event [{}] to Kafka for aggregate [{}]",
                                        event.getId(), event.getAggregateId());
                            } else {
                                event.setRetryCount(event.getRetryCount() + 1);
                                if (event.getRetryCount() >= 5) {
                                    event.setStatus(OutboxStatus.FAILED);
                                    log.error("Outbox event [{}] exceeded maximum retries. Marked as FAILED.", event.getId(), ex);
                                }
                                outboxEventRepository.save(event);
                            }
                        });
            } catch (Exception e) {
                log.error("Error initiating Kafka send for outbox event [{}]: {}", event.getId(), e.getMessage());
                event.setRetryCount(event.getRetryCount() + 1);
                outboxEventRepository.save(event);
            }
        }
    }
}
