package com.nexusfin.notification.consumer;

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

    @Value("${nexusfin.redis.channels.notifications-transactions:nexusfin.notifications.transactions}")
    private String channelName;

    @KafkaListener(
            topics = "${nexusfin.kafka.topics.ledger-transactions:nexusfin.ledger.transactions}",
            groupId = "${spring.kafka.consumer.group-id:nexusfin-notification-group}"
    )
    public void onTransactionPosted(String message) {
        log.info("Received transaction from Kafka. Publishing to Redis cluster channel [{}]: {}", channelName, message);
        try {
            redisTemplate.convertAndSend(channelName, message);
        } catch (Exception ex) {
            log.error("Failed to publish transaction to Redis Pub/Sub: {}", ex.getMessage(), ex);
        }
    }
}
