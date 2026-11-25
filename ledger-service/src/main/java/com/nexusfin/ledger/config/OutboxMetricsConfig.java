package com.nexusfin.ledger.config;

import com.nexusfin.ledger.domain.model.OutboxStatus;
import com.nexusfin.ledger.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Prometheus / Micrometer metrics for Transactional Outbox backlog depth.
 */
@Configuration
@RequiredArgsConstructor
public class OutboxMetricsConfig {

    private final OutboxEventRepository outboxEventRepository;

    @Bean
    public Gauge outboxPendingEventsGauge(MeterRegistry meterRegistry) {
        return Gauge.builder("outbox.pending.events", outboxEventRepository, repo -> {
                    try {
                        return repo.countByStatus(OutboxStatus.PENDING);
                    } catch (Exception ex) {
                        return 0L;
                    }
                })
                .description("Number of pending transactional outbox events awaiting Kafka dispatch")
                .register(meterRegistry);
    }
}
