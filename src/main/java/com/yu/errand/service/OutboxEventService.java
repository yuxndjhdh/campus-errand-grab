package com.yu.errand.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.errand.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class OutboxEventService {
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxEventService(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(String eventType, long bizId) {
        enqueue(eventType, bizId, Map.of("orderId", bizId));
    }

    @Transactional
    public void enqueue(String eventType, long bizId, Object payload) {
        try {
            outbox.insert(eventType, bizId, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize outbox payload", ex);
        }
    }
}
