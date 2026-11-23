package com.nexusfin.fraud.service;

import com.nexusfin.fraud.dto.DltMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory thread-safe store for quarantined Dead-Letter Topic (DLT) messages.
 * Provides administrative visibility and replay capabilities.
 */
@Service
public class DltMessageStore {

    private static final int MAX_RECORDS = 1000;
    private final ConcurrentLinkedDeque<DltMessage> messageList = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, DltMessage> messageMap = new ConcurrentHashMap<>();
    private final AtomicLong totalQuarantinedCount = new AtomicLong(0);

    public void addMessage(DltMessage message) {
        if (message == null || message.getId() == null) {
            return;
        }
        totalQuarantinedCount.incrementAndGet();
        messageMap.put(message.getId(), message);
        messageList.addFirst(message);

        // Evict oldest if beyond capacity
        while (messageList.size() > MAX_RECORDS) {
            DltMessage evicted = messageList.pollLast();
            if (evicted != null) {
                messageMap.remove(evicted.getId());
            }
        }
    }

    public List<DltMessage> getMessages() {
        return new ArrayList<>(messageList);
    }

    public Optional<DltMessage> getMessage(String id) {
        return Optional.ofNullable(messageMap.get(id));
    }

    public boolean remove(String id) {
        DltMessage removed = messageMap.remove(id);
        if (removed != null) {
            messageList.remove(removed);
            return true;
        }
        return false;
    }

    public long getTotalCount() {
        return totalQuarantinedCount.get();
    }

    public void clear() {
        messageList.clear();
        messageMap.clear();
        totalQuarantinedCount.set(0);
    }
}
