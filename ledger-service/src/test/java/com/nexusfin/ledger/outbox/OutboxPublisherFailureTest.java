package com.nexusfin.ledger.outbox;

import com.nexusfin.ledger.AbstractPostgresIT;
import com.nexusfin.ledger.domain.entity.OutboxEvent;
import com.nexusfin.ledger.domain.model.OutboxStatus;
import com.nexusfin.ledger.repository.OutboxEventRepository;
import com.nexusfin.ledger.service.OutboxPublisherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {
        "nexusfin.kafka.outbox.enabled=true",
        "nexusfin.kafka.outbox.poll-interval-ms=100000",
        "nexusfin.kafka.outbox.max-retries=5",
        "nexusfin.kafka.outbox.batch-size=50"
})
class OutboxPublisherFailureTest extends AbstractPostgresIT {

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
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
    @DisplayName("1.1 Bug Proof 1: Exception during dispatch must cap at maxRetries and set status to FAILED")
    void brokerDown_exceptionMustSetStatusToFailedWhenMaxRetriesReached() {
        // Mock Kafka broker failure (synchronous exception on send initiation)
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Kafka broker unreachable"));

        // Create an event that already has retryCount = 4 (maxRetries is 5)
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("TRANSACTION")
                .aggregateId("TX-FAIL-TEST")
                .eventType("TransactionPostedEvent")
                .payload("{\"test\":\"fail\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(4)
                .build();
        event = outboxEventRepository.save(event);

        // Current code has catch (Exception e) which increments retryCount to 5,
        // but NEVER sets status to FAILED!
        outboxPublisherService.dispatchPendingEvents();

        OutboxEvent updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getRetryCount()).isEqualTo(5);
        assertThat(updated.getStatus())
                .withFailMessage("Expected status to be FAILED after reaching max retries (5), but was %s", updated.getStatus())
                .isEqualTo(OutboxStatus.FAILED);
    }
}
