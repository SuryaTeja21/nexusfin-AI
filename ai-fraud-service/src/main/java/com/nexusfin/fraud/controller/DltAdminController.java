package com.nexusfin.fraud.controller;

import com.nexusfin.fraud.dto.DltMessage;
import com.nexusfin.fraud.service.DltMessageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Administrative REST Controller for inspecting and replaying Dead-Letter Topic (DLT) quarantined messages.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fraud/dlt")
@RequiredArgsConstructor
public class DltAdminController {

    private final DltMessageStore dltMessageStore;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @GetMapping("/messages")
    public ResponseEntity<Map<String, Object>> getQuarantinedMessages() {
        List<DltMessage> messages = dltMessageStore.getMessages();
        return ResponseEntity.ok(Map.of(
                "totalCount", dltMessageStore.getTotalCount(),
                "activeQuarantinedCount", messages.size(),
                "messages", messages
        ));
    }

    @PostMapping("/replay/{id}")
    public ResponseEntity<Map<String, Object>> replayMessage(@PathVariable String id) {
        DltMessage message = dltMessageStore.getMessage(id)
                .orElseThrow(() -> new IllegalArgumentException("DLT message with id [" + id + "] not found"));

        String destinationTopic = message.getOriginalTopic() != null ? message.getOriginalTopic() : "nexusfin.ledger.transactions";
        log.info("Admin replaying quarantined DLT message [{}] to target topic [{}]", id, destinationTopic);

        kafkaTemplate.send(destinationTopic, message.getPayload());
        dltMessageStore.remove(id);

        return ResponseEntity.ok(Map.of(
                "status", "REPLAYED",
                "messageId", id,
                "targetTopic", destinationTopic
        ));
    }
}
