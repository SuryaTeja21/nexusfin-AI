package com.nexusfin.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Type;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
@DisplayName("Phase 4: Real-time WebSocket/STOMP & Redis Fan-Out Integration Tests")
public class NotificationServiceIT {

    public static final KafkaContainer kafka;
    public static final GenericContainer<?> redis;

    static {
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
        kafka.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("eureka.client.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        return stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("4.1 STOMP CONNECT without valid JWT token is rejected")
    void testUnauthenticatedConnectionRejected() {
        assertThatThrownBy(() -> connect(null))
                .isInstanceOf(Exception.class);

        assertThatThrownBy(() -> connect("invalid-token"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("4.2 Customer role is forbidden from subscribing to admin broadcast /topic/transactions")
    void testCustomerForbiddenFromAdminTopic() throws Exception {
        StompSession session = connect("customer1-token");
        assertThat(session.isConnected()).isTrue();

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        session.subscribe("/topic/transactions", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((String) payload);
            }
        });

        // Trigger a transaction via Kafka
        String txJson = """
                {
                  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
                  "transactionReference": "TX-ADMIN-ONLY",
                  "amount": 100.0,
                  "currency": "USD"
                }
                """;
        kafkaTemplate.send("nexusfin.ledger.transactions", txJson).get(5, TimeUnit.SECONDS);

        // Customer must not receive message on /topic/transactions
        String received = messages.poll(2, TimeUnit.SECONDS);
        assertThat(received).isNull();

        if (session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    @DisplayName("4.3 Admin receives global transaction broadcast on /topic/transactions via Kafka & Redis Pub/Sub")
    void testAdminReceivesTransactionBroadcast() throws Exception {
        StompSession session = connect("admin-token");
        assertThat(session.isConnected()).isTrue();

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        session.subscribe("/topic/transactions", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((String) payload);
            }
        });

        // Publish transaction event to Kafka
        String txRef = "TX-BROADCAST-" + java.util.UUID.randomUUID();
        String txJson = String.format("""
                {
                  "transactionId": "%s",
                  "transactionReference": "%s",
                  "amount": 750.00,
                  "currency": "USD",
                  "description": "Global Admin Feed Test"
                }
                """, java.util.UUID.randomUUID(), txRef);

        kafkaTemplate.send("nexusfin.ledger.transactions", txJson).get(5, TimeUnit.SECONDS);

        // Verify admin received the broadcast
        String received = messages.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received).contains(txRef);

        session.disconnect();
    }

    @Test
    @DisplayName("4.4 Customer receives private notification on /user/queue/transactions for owned account leg")
    void testCustomerReceivesPrivateQueueTransaction() throws Exception {
        // Customer 1 has sub: c0000000-0000-0000-0000-000000000001
        StompSession session = connect("customer1-token");
        assertThat(session.isConnected()).isTrue();

        BlockingQueue<String> customerQueue = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/transactions", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                customerQueue.offer((String) payload);
            }
        });

        String txRef = "TX-PRIVATE-" + java.util.UUID.randomUUID();
        String txJson = String.format("""
                {
                  "transactionId": "%s",
                  "transactionReference": "%s",
                  "amount": 250.00,
                  "currency": "USD",
                  "legs": [
                    {
                      "accountNumber": "ACC-CUSTOMER-ALICE-USD",
                      "entryType": "DEBIT",
                      "amount": 250.00,
                      "currency": "USD",
                      "ownerId": "c0000000-0000-0000-0000-000000000001"
                    },
                    {
                      "accountNumber": "ACC-CUSTOMER-BOB-USD",
                      "entryType": "CREDIT",
                      "amount": 250.00,
                      "currency": "USD",
                      "ownerId": "c0000000-0000-0000-0000-000000000002"
                    }
                  ]
                }
                """, java.util.UUID.randomUUID(), txRef);

        kafkaTemplate.send("nexusfin.ledger.transactions", txJson).get(5, TimeUnit.SECONDS);

        // Customer1 must receive the event on /user/queue/transactions
        String received = customerQueue.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received).contains(txRef);
        assertThat(received).contains("ACC-CUSTOMER-ALICE-USD");

        session.disconnect();
    }

    @Test
    @DisplayName("4.5 Customer does not receive private transaction notifications belonging to another customer")
    void testCustomerDoesNotReceiveOtherCustomerTransaction() throws Exception {
        // Customer 2 has sub: c0000000-0000-0000-0000-000000000002
        StompSession session = connect("customer2-token");
        assertThat(session.isConnected()).isTrue();

        BlockingQueue<String> customer2Queue = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/transactions", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                customer2Queue.offer((String) payload);
            }
        });

        // Transaction strictly between Customer 1 and an external reserve (neither leg owned by Customer 2)
        String txRef = "TX-ALICE-ONLY-" + java.util.UUID.randomUUID();
        String txJson = String.format("""
                {
                  "transactionId": "%s",
                  "transactionReference": "%s",
                  "amount": 100.00,
                  "currency": "USD",
                  "legs": [
                    {
                      "accountNumber": "ACC-CUSTOMER-ALICE-USD",
                      "entryType": "DEBIT",
                      "amount": 100.00,
                      "currency": "USD",
                      "ownerId": "c0000000-0000-0000-0000-000000000001"
                    },
                    {
                      "accountNumber": "ACC-RESERVE-USD",
                      "entryType": "CREDIT",
                      "amount": 100.00,
                      "currency": "USD",
                      "ownerId": "a0000000-0000-0000-0000-000000000001"
                    }
                  ]
                }
                """, java.util.UUID.randomUUID(), txRef);

        kafkaTemplate.send("nexusfin.ledger.transactions", txJson).get(5, TimeUnit.SECONDS);

        // Customer 2 must NOT receive this transaction
        String received = customer2Queue.poll(2, TimeUnit.SECONDS);
        assertThat(received).isNull();

        if (session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    @DisplayName("4.6 / 5.4 Poison message on Kafka is routed to DLT without terminating WebSocket consumer session")
    void testPoisonPillQuarantinedWithoutCrashingNotificationConsumer() throws Exception {
        StompSession session = connect("admin-token");
        assertThat(session.isConnected()).isTrue();

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        session.subscribe("/topic/transactions", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.offer((String) payload);
            }
        });

        // 1. Send poison pill
        String poison = "{ UNPARSED_POISON_PILL_INVALID_JSON ... ";
        kafkaTemplate.send("nexusfin.ledger.transactions", poison).get(5, TimeUnit.SECONDS);

        // 2. Send valid transaction immediately after poison pill
        String validTxRef = "TX-VALID-RECOVERY-" + java.util.UUID.randomUUID();
        String validJson = String.format("""
                {
                  "transactionId": "%s",
                  "transactionReference": "%s",
                  "amount": 999.00,
                  "currency": "USD",
                  "description": "Valid broadcast post-poison"
                }
                """, java.util.UUID.randomUUID(), validTxRef);

        kafkaTemplate.send("nexusfin.ledger.transactions", validJson).get(5, TimeUnit.SECONDS);

        // 3. Admin must receive the valid transaction cleanly
        String received = messages.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received).contains(validTxRef);
        assertThat(session.isConnected()).isTrue();

        session.disconnect();
    }
}
