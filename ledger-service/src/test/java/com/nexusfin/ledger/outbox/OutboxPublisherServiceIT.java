package com.nexusfin.ledger.outbox;

import com.nexusfin.ledger.AbstractPostgresIT;
import com.nexusfin.ledger.domain.entity.OutboxEvent;
import com.nexusfin.ledger.domain.model.OutboxStatus;
import com.nexusfin.ledger.repository.OutboxEventRepository;
import com.nexusfin.ledger.service.OutboxPublisherService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@DirtiesContext
@AutoConfigureObservability
@TestPropertySource(properties = {
        "nexusfin.kafka.outbox.enabled=true",
        "nexusfin.kafka.outbox.poll-interval-ms=100000",
        "nexusfin.kafka.outbox.max-retries=5",
        "nexusfin.kafka.outbox.batch-size=50"
})
class OutboxPublisherServiceIT extends AbstractPostgresIT {

    public static final KafkaContainer kafka;

    static {
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
        kafka.start();
    }

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.producer.key-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        registry.add("spring.kafka.producer.value-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
    }

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("1.1 Bug Proof 2: Concurrent dispatchers on pending events without SKIP LOCKED cause duplicate Kafka publishing")
    void concurrentPublishers_mustNotPublishSameEventTwice() throws Exception {
        int eventCount = 10;
        List<UUID> eventIds = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType("TRANSACTION")
                    .aggregateId("TX-CONCURRENT-" + i)
                    .eventType("TransactionPostedEvent")
                    .payload("{\"index\":" + i + "}")
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(Instant.now().minusSeconds(eventCount - i))
                    .build();
            event = outboxEventRepository.save(event);
            eventIds.add(event.getId());
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(2);

        executor.submit(() -> {
            try {
                startGate.await();
                outboxPublisherService.dispatchPendingEvents();
            } catch (Exception e) {
                // ignore
            } finally {
                endGate.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startGate.await();
                outboxPublisherService.dispatchPendingEvents();
            } catch (Exception e) {
                // ignore
            } finally {
                endGate.countDown();
            }
        });

        startGate.countDown();
        boolean completed = endGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        KafkaConsumer<String, String> consumer = createConsumer("test-concurrent-group");
        consumer.subscribe(Collections.singletonList("nexusfin.ledger.transactions"));

        int totalMessagesReceived = 0;
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            totalMessagesReceived += records.count();
            if (totalMessagesReceived >= eventCount && records.isEmpty()) {
                break;
            }
        }
        consumer.close();

        // In fixed code with SELECT ... FOR UPDATE SKIP LOCKED and synchronous sending in transaction:
        // exactly eventCount (10) messages are published.
        // In current buggy code without SKIP LOCKED, both threads select the same PENDING events,
        // resulting in duplicate messages published (> 10)!
        assertThat(totalMessagesReceived)
                .withFailMessage("Expected exactly %d messages but received %d (duplicate dispatch detected due to missing SKIP LOCKED)",
                        eventCount, totalMessagesReceived)
                .isEqualTo(eventCount);
    }

    @Test
    @DisplayName("1.1 Bug Proof 3: Event status must be synchronously marked PUBLISHED within the transaction")
    void happyPath_eventPublishedSynchronouslyInTransaction() {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("TRANSACTION")
                .aggregateId("TX-SYNC-1")
                .eventType("TransactionPostedEvent")
                .payload("{\"test\":\"sync\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
        event = outboxEventRepository.save(event);

        // In current code, kafkaTemplate.send() is async, and whenComplete runs in the background outside the transaction.
        // Calling dispatchPendingEvents() returns BEFORE whenComplete has executed or saved to DB.
        outboxPublisherService.dispatchPendingEvents();

        // Immediately check DB state
        OutboxEvent updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .withFailMessage("Expected status PUBLISHED immediately after dispatchPendingEvents() returns, but was %s", updated.getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updated.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("6.3 Outbox dispatch propagates traceparent and X-Correlation-Id baggage headers to Kafka")
    void outboxDispatchPropagatesTraceContextToKafka() {
        String upstreamTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceParent = "00-" + upstreamTraceId + "-00f067aa0ba902b7-01";
        String correlationId = "CORR-OUTBOX-TRACE-999";

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("TRANSACTION")
                .aggregateId("TX-TRACE-" + UUID.randomUUID().toString().substring(0, 8))
                .eventType("TRANSACTION_POSTED")
                .payload("{\"test\":\"trace\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .correlationId(correlationId)
                .traceParent(traceParent)
                .build();
        event = outboxEventRepository.save(event);

        KafkaConsumer<String, String> consumer = createConsumer("test-trace-group-" + UUID.randomUUID());
        consumer.subscribe(Collections.singletonList("nexusfin.ledger.transactions"));
        consumer.poll(Duration.ofMillis(500));

        outboxPublisherService.dispatchPendingEvents();

        String receivedTraceParent = null;
        String receivedBaggage = null;
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && receivedTraceParent == null) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (var rec : records) {
                if (event.getAggregateId().equals(rec.key())) {
                    var tpHeader = rec.headers().lastHeader("traceparent");
                    var bgHeader = rec.headers().lastHeader("baggage");
                    if (tpHeader != null) {
                        receivedTraceParent = new String(tpHeader.value(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    if (bgHeader != null) {
                        receivedBaggage = new String(bgHeader.value(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        }
        consumer.close();

        assertThat(receivedTraceParent).isNotNull();
        assertThat(receivedTraceParent).contains(upstreamTraceId);
        assertThat(receivedBaggage).contains("X-Correlation-Id=" + correlationId);
    }

    private KafkaConsumer<String, String> createConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
