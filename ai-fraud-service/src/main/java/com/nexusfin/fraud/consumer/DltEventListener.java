package com.nexusfin.fraud.consumer;

import com.nexusfin.fraud.dto.DltMessage;
import com.nexusfin.fraud.service.DltMessageStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka listener indexing messages that arrive in the Dead Letter Topic (DLT).
 * Provides persistence into DltMessageStore for administrative inspection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexusfin.kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class DltEventListener {

    private final DltMessageStore dltMessageStore;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${nexusfin.kafka.topics.ledger-transactions-dlt:nexusfin.ledger.transactions.DLT}",
            groupId = "${spring.kafka.consumer.dlt-group-id:nexusfin-fraud-dlt-group}"
    )
    public void onDltMessage(ConsumerRecord<String, String> record) {
        log.warn("Observed quarantined message on DLT [{}]: partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());

        String originalTopic = getHeaderString(record, "kafka_dlt-original-topic");
        String exceptionClass = getHeaderString(record, "kafka_dlt-exception-fqcn");
        String exceptionMessage = getHeaderString(record, "kafka_dlt-exception-message");

        DltMessage dltMessage = DltMessage.builder()
                .id(UUID.randomUUID().toString())
                .originalTopic(originalTopic != null ? originalTopic : "nexusfin.ledger.transactions")
                .partition(record.partition())
                .offset(record.offset())
                .payload(record.value())
                .exceptionClass(exceptionClass)
                .exceptionMessage(exceptionMessage)
                .quarantinedAt(Instant.now())
                .build();

        dltMessageStore.addMessage(dltMessage);
        meterRegistry.counter("dlt.messages.indexed", "topic", record.topic()).increment();
    }

    private String getHeaderString(ConsumerRecord<?, ?> record, String headerKey) {
        Header header = record.headers().lastHeader(headerKey);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }
}
