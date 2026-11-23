package com.nexusfin.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Broadcasts transaction event to STOMP destinations:
     * 1. Public admin broadcast channel: /topic/transactions
     * 2. Private customer queues: /user/{ownerId}/queue/transactions for affected account owners
     */
    public void broadcast(String transactionPayloadJson) {
        log.info("Broadcasting transaction notification via WebSocket STOMP: {}", transactionPayloadJson);

        try {
            JsonNode root = objectMapper.readTree(transactionPayloadJson);

            // 1. Broadcast to admins on /topic/transactions
            messagingTemplate.convertAndSend("/topic/transactions", transactionPayloadJson);

            // 2. Identify affected account owners from the transaction legs
            Set<String> affectedOwners = new HashSet<>();
            JsonNode legs = root.get("legs");
            if (legs != null && legs.isArray()) {
                for (JsonNode leg : legs) {
                    JsonNode ownerNode = leg.get("ownerId");
                    if (ownerNode != null && !ownerNode.isNull() && !ownerNode.asText().isBlank()) {
                        affectedOwners.add(ownerNode.asText().trim());
                    }
                }
            }

            // Also check top-level ownerId if present
            if (root.has("ownerId") && !root.get("ownerId").isNull()) {
                affectedOwners.add(root.get("ownerId").asText().trim());
            }

            // 3. Deliver to private customer queues
            for (String ownerId : affectedOwners) {
                log.info("Routing private transaction notification to owner: {}", ownerId);
                messagingTemplate.convertAndSendToUser(ownerId, "/queue/transactions", transactionPayloadJson);
            }
        } catch (Exception ex) {
            log.error("Failed to parse and broadcast transaction event: {}", ex.getMessage(), ex);
        }
    }
}
