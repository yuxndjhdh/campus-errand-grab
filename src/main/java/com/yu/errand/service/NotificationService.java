package com.yu.errand.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.errand.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final OutboxEventService outbox;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository notifications, OutboxEventService outbox,
                               ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public long enqueue(long userId, String eventKey, String type, String payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(Map.of(
                    "eventKey", eventKey,
                    "payload", payload
            ));
            notifications.insert(eventKey, userId, type, payloadJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize notification payload", ex);
        }
        long notificationId = notifications.lastId(eventKey);
        outbox.enqueue(type + "_NOTIFICATION", notificationId, java.util.Map.of("eventKey", eventKey));
        return notificationId;
    }

    @Transactional
    public void markSent(long notificationId) {
        notifications.markSent(notificationId);
    }
}
