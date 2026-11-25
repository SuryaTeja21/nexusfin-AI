package com.nexusfin.ledger.service;

import com.nexusfin.ledger.domain.entity.OutboxEvent;
import com.nexusfin.ledger.domain.model.OutboxStatus;
import com.nexusfin.ledger.repository.OutboxEventRepository;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
@ConditionalOnProperty(name = "nexusfin.kafka.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Autowired(required = false)
    private Tracer tracer;

    @Autowired(required = false)
    private Propagator propagator;

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
            dispatchSingleEvent(event);
        }
    }

    private void dispatchSingleEvent(OutboxEvent event) {
        String correlationId = event.getCorrelationId();
        boolean hasCorrelationId = correlationId != null && !correlationId.isBlank();
        if (hasCorrelationId) {
            MDC.put("X-Correlation-Id", correlationId);
        }

        Span span = startOutboxDispatchSpan(event);
        try (Tracer.SpanInScope spanScope = (span != null && tracer != null) ? tracer.withSpan(span) : null;
             BaggageInScope baggageScope = bindCorrelationBaggage(span, correlationId)) {
            // Synchronous wait ensures event publication completes within the transaction boundary
            kafkaTemplate.send(topicName, event.getAggregateId(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);

            event.setStatus(OutboxStatus.PUBLISHED);
            event.setProcessedAt(Instant.now());
            outboxEventRepository.save(event);
            log.info("Successfully dispatched outbox event [{}] to Kafka for aggregate [{}]",
                    event.getId(), event.getAggregateId());
        } catch (Exception e) {
            if (span != null) {
                span.error(e);
            }
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
        } finally {
            if (span != null) {
                span.end();
            }
            if (hasCorrelationId) {
                MDC.remove("X-Correlation-Id");
            }
        }
    }

    private Span startOutboxDispatchSpan(OutboxEvent event) {
        if (tracer == null) {
            return null;
        }
        Span.Builder builder;
        if (propagator != null && event.getTraceParent() != null && !event.getTraceParent().isBlank()) {
            Map<String, String> carrier = new HashMap<>();
            carrier.put("traceparent", event.getTraceParent());
            if (event.getCorrelationId() != null && !event.getCorrelationId().isBlank()) {
                carrier.put("baggage", "X-Correlation-Id=" + event.getCorrelationId());
            }
            builder = propagator.extract(carrier, Map::get);
        } else {
            builder = tracer.spanBuilder();
        }
        Span span = builder.name("outbox.dispatch")
                .kind(Span.Kind.PRODUCER)
                .tag("outbox.event.id", String.valueOf(event.getId()))
                .tag("outbox.aggregate.id", String.valueOf(event.getAggregateId()))
                .start();
        if (event.getCorrelationId() != null && !event.getCorrelationId().isBlank()) {
            span.tag("correlation.id", event.getCorrelationId());
        }
        return span;
    }

    private BaggageInScope bindCorrelationBaggage(Span span, String correlationId) {
        if (tracer == null || correlationId == null || correlationId.isBlank()) {
            return null;
        }
        if (span != null) {
            return tracer.createBaggageInScope(span.context(), "X-Correlation-Id", correlationId);
        }
        return tracer.createBaggageInScope("X-Correlation-Id", correlationId);
    }
}
