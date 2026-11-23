package com.nexusfin.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionKafkaConsumer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${nexusfin.redis.channels.notifications-transactions:nexusfin.notifications.transactions}")
    private String channelName;

    @KafkaListener(
            topics = "${nexusfin.kafka.topics.ledger-transactions:nexusfin.ledger.transactions}",
            groupId = "${spring.kafka.consumer.group-id:nexusfin-notification-group}"
    )
    public void onTransactionPosted(String message) {
        log.info("Received transaction from Kafka. Publishing to Redis cluster channel [{}]: {}", channelName, message);
        try {
            // Validate payload before propagating to Redis cluster fan-out
            objectMapper.readTree(message);
            redisTemplate.convertAndSend(channelName, message);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.error("Poison message (malformed JSON) detected in notification consumer: {}", ex.getMessage());
            throw new IllegalArgumentException("Malformed JSON in transaction event: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("Failed to publish transaction to Redis Pub/Sub: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error processing Kafka notification event", ex);
        }
    }
}
