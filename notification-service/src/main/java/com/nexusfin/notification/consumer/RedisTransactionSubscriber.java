package com.nexusfin.notification.consumer;

import com.nexusfin.notification.service.NotificationBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTransactionSubscriber implements MessageListener {

    private final NotificationBroadcaster notificationBroadcaster;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("Received fan-out transaction event from Redis Pub/Sub channel: {}", payload);
        notificationBroadcaster.broadcast(payload);
    }
}
