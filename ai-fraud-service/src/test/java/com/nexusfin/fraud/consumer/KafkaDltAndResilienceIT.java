package com.nexusfin.fraud.consumer;

import com.nexusfin.fraud.AiFraudServiceApplication;
import com.nexusfin.fraud.dto.DltMessage;
import com.nexusfin.fraud.repository.AuditLogRepository;
import com.nexusfin.fraud.repository.ProcessedEventRepository;
import com.nexusfin.fraud.service.DltMessageStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AiFraudServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Phase 5: Kafka Dead Letter Topic (DLT) & Resilience Integration Tests")
public class KafkaDltAndResilienceIT {

    public static final KafkaContainer kafka;

    static {
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
        kafka.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("nexusfin.kafka.consumer.enabled", () -> "true");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("eureka.client.enabled", () -> "false");
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private DltMessageStore dltMessageStore;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dltMessageStore.clear();
        auditLogRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("5.1 Poison pill message (malformed JSON) is quarantined to DLT without crashing consumer")
    void testPoisonPillQuarantinedToDlt() throws Exception {
        String poisonMessage = "{ this_is_invalid_json: true, unterminated_payload";

        // 1. Send poison message to primary ledger transactions topic
        kafkaTemplate.send("nexusfin.ledger.transactions", poisonMessage).get(5, TimeUnit.SECONDS);

        // 2. Wait for DeadLetterPublishingRecoverer & DltEventListener to process and store the quarantined record
        await().atMost(10, TimeUnit.SECONDS).until(() -> dltMessageStore.getTotalCount() > 0);

        assertThat(dltMessageStore.getTotalCount()).isGreaterThanOrEqualTo(1);
        DltMessage quarantined = dltMessageStore.getMessages().get(0);
        assertThat(quarantined).isNotNull();
        assertThat(quarantined.getPayload()).isEqualTo(poisonMessage);
        assertThat(quarantined.getOriginalTopic()).isEqualTo("nexusfin.ledger.transactions");

        // 3. Verify Micrometer metric 'dlt.messages' was incremented
        double dltCount = meterRegistry.get("dlt.messages").counter().count();
        assertThat(dltCount).isGreaterThanOrEqualTo(1.0);

        // 4. Verify admin visibility endpoint GET /api/v1/fraud/dlt/messages
        mockMvc.perform(get("/api/v1/fraud/dlt/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.messages").isArray());
    }

    @Test
    @DisplayName("5.2 Valid transaction published after poison pill is processed successfully (no head-of-line blocking)")
    void testValidMessageProcessedAfterPoisonPill() throws Exception {
        String poisonMessage = "MALFORMED_UNPARSEABLE_STRING_PAYLOAD_POISON_PILL";
        kafkaTemplate.send("nexusfin.ledger.transactions", poisonMessage).get(5, TimeUnit.SECONDS);

        // Ensure poison pill is quarantined
        await().atMost(10, TimeUnit.SECONDS).until(() -> dltMessageStore.getTotalCount() > 0);

        // Now publish a valid double-entry transaction event
        String validTxRef = "TX-VALID-AFTER-POISON-" + UUID.randomUUID();
        String validJson = String.format("""
                {
                  "transactionId": "%s",
                  "transactionReference": "%s",
                  "transactionType": "TRANSFER",
                  "amount": 1500.00,
                  "currency": "USD",
                  "sourceAccountNumber": "ACC-SOURCE-USD",
                  "destinationAccountNumber": "ACC-DEST-USD",
                  "description": "Legitimate transaction following poison pill"
                }
                """, UUID.randomUUID(), validTxRef);

        kafkaTemplate.send("nexusfin.ledger.transactions", validJson).get(5, TimeUnit.SECONDS);

        // Assert that the valid transaction is processed, evaluated, and stored
        await().atMost(10, TimeUnit.SECONDS).until(() -> processedEventRepository.existsById(validTxRef));

        assertThat(processedEventRepository.existsById(validTxRef)).isTrue();
        assertThat(auditLogRepository.findByTransactionReferenceOrderByCreatedAtDesc(validTxRef)).isNotEmpty();
    }

    @Test
    @DisplayName("5.3 Admin replay endpoint republishes message from DLT back to processing topic")
    void testAdminReplayFromDlt() throws Exception {
        // Manually place a quarantined message into store
        String replayTxRef = "TX-REPLAY-" + UUID.randomUUID();
        String validJson = String.format("""
                {
                  "transactionId": "%s",
                  "transactionReference": "%s",
                  "transactionType": "TRANSFER",
                  "amount": 350.00,
                  "currency": "USD",
                  "sourceAccountNumber": "ACC-REPLAY-SRC",
                  "destinationAccountNumber": "ACC-REPLAY-DST",
                  "description": "Replayed transaction from DLT"
                }
                """, UUID.randomUUID(), replayTxRef);

        String messageId = UUID.randomUUID().toString();
        dltMessageStore.addMessage(DltMessage.builder()
                .id(messageId)
                .originalTopic("nexusfin.ledger.transactions")
                .partition(0)
                .offset(10L)
                .payload(validJson)
                .exceptionClass("TransientFailureException")
                .exceptionMessage("Temporary service blip")
                .quarantinedAt(java.time.Instant.now())
                .build());

        assertThat(dltMessageStore.getMessage(messageId)).isPresent();

        // Admin invokes replay endpoint
        mockMvc.perform(post("/api/v1/fraud/dlt/replay/" + messageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andExpect(jsonPath("$.messageId").value(messageId));

        // Message should be removed from quarantine and processed into repository
        assertThat(dltMessageStore.getMessage(messageId)).isEmpty();

        await().atMost(10, TimeUnit.SECONDS).until(() -> processedEventRepository.existsById(replayTxRef));
        assertThat(processedEventRepository.existsById(replayTxRef)).isTrue();
    }
}
